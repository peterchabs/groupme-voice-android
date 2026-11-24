#!/data/data/com.termux/files/usr/bin/env python3
"""
groupme_send.py - send a GroupMe message from your own account, by voice.

Runs in Termux on Android. Standard library only (no pip installs).

Usage:
  groupme_send.py "send a message to Weekend Crew that says running 10 minutes late"
  groupme_send.py --group "Weekend Crew" --text "running 10 minutes late"
  groupme_send.py --list                 # show your groups + ids
  groupme_send.py --dry-run "..."        # resolve the group, don't send

Token: put your GroupMe access token (dev.groupme.com -> "Access Token")
in ~/.config/groupme-voice/token  (chmod 600), or set GROUPME_TOKEN.

Optional aliases: ~/.config/groupme-voice/aliases.json
  {"crew": "12345678", "family": "87654321"}
Spoken names that match an alias go straight to that group id.
"""
import argparse
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://api.groupme.com/v3"
CONF_DIR = os.path.expanduser("~/.config/groupme-voice")
TOKEN_FILE = os.path.join(CONF_DIR, "token")
ALIAS_FILE = os.path.join(CONF_DIR, "aliases.json")
MAX_TEXT = 1000          # GroupMe's message length limit
MATCH_CUTOFF = 0.6       # how close a spoken name must be to a real group name


# ---------- feedback on the phone (falls back to stdout) ----------
def notify(msg, speak=True):
    print(msg)
    if shutil.which("termux-toast"):
        subprocess.run(["termux-toast", msg], check=False)
    if speak and shutil.which("termux-tts-speak"):
        subprocess.run(["termux-tts-speak", msg], check=False)


def fail(msg):
    notify(msg)
    sys.exit(1)


# ---------- token ----------
def load_token():
    tok = os.environ.get("GROUPME_TOKEN", "").strip()
    if tok:
        return tok
    try:
        mode = os.stat(TOKEN_FILE).st_mode & 0o077
        if mode:
            print(f"warning: {TOKEN_FILE} is readable by others; run chmod 600 on it",
                  file=sys.stderr)
        with open(TOKEN_FILE) as f:
            tok = f.read().strip()
    except FileNotFoundError:
        tok = ""
    if not tok:
        fail("No GroupMe token. Save it to ~/.config/groupme-voice/token")
    return tok


# ---------- API ----------
def api(method, path, token, body=None, params=None):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "X-Access-Token": token,           # header, so the token stays out of URLs/logs
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        if e.code == 401:
            fail("GroupMe rejected the token. Get a fresh one at dev.groupme.com.")
        fail(f"GroupMe error {e.code}: {e.read().decode(errors='replace')[:200]}")
    except urllib.error.URLError as e:
        fail(f"Could not reach GroupMe: {e.reason}")


def my_groups(token):
    groups, page = [], 1
    while True:
        resp = api("GET", "/groups", token,
                   params={"per_page": 100, "page": page, "omit": "memberships"})
        batch = resp.get("response") or []
        groups.extend({"id": g["id"], "name": g.get("name") or ""} for g in batch)
        if len(batch) < 100:
            return groups
        page += 1


# ---------- parsing the spoken phrase ----------
LEAD = re.compile(
    r"^\s*(?:hey google[,\s]*|ok google[,\s]*)?"
    r"(?:please\s+)?(?:groupme\s+)?"
    r"(?:(?:send|text|post|message)\s+(?:a\s+)?(?:groupme\s+)?(?:message\s+)?(?:(?:to|in)\s+)?)?"
    r"(?:the\s+)?(?:groupme\s+)?(?:group\s+)?",
    re.I)
SPLIT = re.compile(r"\s+(?:that says|saying|which says|and say|message)\s*[:,]?\s+", re.I)


def parse_phrase(phrase):
    # AutoWear (watch) can deliver "groupme=:=<what you said>"; drop the tag.
    phrase = re.sub(r"^\s*[\w-]+=:=\s*", "", phrase)
    rest = LEAD.sub("", phrase, count=1)
    parts = SPLIT.split(rest, maxsplit=1)
    if len(parts) != 2:
        return None, None
    group, text = parts[0].strip(" ,.:"), parts[1].strip()
    group = re.sub(r"\s+on groupme$", "", group, flags=re.I)
    group = re.sub(r"\s+(?:group|chat)$", "", group, flags=re.I)
    return group or None, text or None


def norm(s):
    return re.sub(r"[^a-z0-9 ]", "", s.lower()).strip()


def resolve_group(spoken, token):
    # 1. explicit numeric id
    if spoken.isdigit():
        return spoken, spoken
    # 2. alias file
    try:
        with open(ALIAS_FILE) as f:
            aliases = {norm(k): str(v) for k, v in json.load(f).items()}
        if norm(spoken) in aliases:
            return aliases[norm(spoken)], spoken
    except FileNotFoundError:
        pass
    except json.JSONDecodeError:
        print("warning: aliases.json is not valid JSON; ignoring it", file=sys.stderr)
    # 3. fuzzy match against the groups YOU belong to
    groups = my_groups(token)
    by_name = {}
    for g in groups:
        by_name.setdefault(norm(g["name"]), []).append(g)
    key = norm(spoken)
    if key in by_name:
        hits = by_name[key]
    else:
        close = difflib.get_close_matches(key, list(by_name), n=2, cutoff=MATCH_CUTOFF)
        # also accept "crew" -> "weekend crew" style partial matches
        partial = [n for n in by_name if key and key in n]
        cands = list(dict.fromkeys(close + partial))
        if not cands:
            fail(f"I couldn't find a group called {spoken}.")
        if len(cands) > 1:     # never guess between two groups
            names = " or ".join(by_name[c][0]["name"] for c in cands[:3])
            fail(f"Which group did you mean: {names}? Nothing was sent.")
        hits = by_name[cands[0]]
    if len(hits) > 1:
        fail(f"You have {len(hits)} groups named {hits[0]['name']}. "
             "Add one to aliases.json. Nothing was sent.")
    return hits[0]["id"], hits[0]["name"]


# ---------- main ----------
def main():
    ap = argparse.ArgumentParser(description="Send a GroupMe message by voice.")
    ap.add_argument("phrase", nargs="*", help='e.g. "send a message to X that says Y"')
    ap.add_argument("--group", help="group name, alias, or numeric id")
    ap.add_argument("--text", help="message text")
    ap.add_argument("--heard-file", help="read the spoken phrase from this file "
                    "(written fresh by Tasker each time), then delete it")
    ap.add_argument("--list", action="store_true", help="list your groups and ids")
    ap.add_argument("--dry-run", action="store_true", help="resolve but don't send")
    a = ap.parse_args()

    token = load_token()

    if a.list:
        for g in sorted(my_groups(token), key=lambda g: g["name"].lower()):
            print(f"{g['id']:>12}  {g['name']}")
        return

    group, text = a.group, a.text
    if a.heard_file:
        path = os.path.expanduser(a.heard_file)
        try:
            with open(path, encoding="utf-8") as f:
                a.phrase = [f.read().strip()]
            os.remove(path)        # never resend a stale phrase on the next run
        except FileNotFoundError:
            fail("I didn't catch a message. Nothing was sent.")
    if a.phrase and not (group and text):
        pg, pt = parse_phrase(" ".join(a.phrase))
        group, text = group or pg, text or pt
    if not group or not text:
        fail('Say it like: send a message to Weekend Crew that says hello.')

    if len(text) > MAX_TEXT:
        fail(f"That message is {len(text)} characters; GroupMe allows {MAX_TEXT}.")

    gid, gname = resolve_group(group, token)

    if a.dry_run:
        notify(f'Dry run: would send "{text}" to {gname} ({gid}).', speak=False)
        return

    api("POST", f"/groups/{gid}/messages", token, body={
        "message": {"source_guid": str(uuid.uuid4()), "text": text}
    })
    notify(f"Sent to {gname}.")


if __name__ == "__main__":
    main()
