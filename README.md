# GroupMe Voice for Android

Send a GroupMe message just by talking to your phone.

You say: **"Hey Google, open GroupMe Voice"**
Your phone asks: *"Which group, and what should I say?"*
You say: **"Weekend Crew that says I'm running 10 minutes late"**
Your phone says: *"Sent to Weekend Crew."*

The message goes out from **your own GroupMe account**, to **your own groups**,
with **whatever words you say each time**.

Setup takes about 15 minutes, one time. You do not need to know how to code.

---

## Before you start, you need

- An Android phone
- A GroupMe account
- About $3.50 for the **Tasker** app (the only paid part)

---

## Part 1: Install the apps

Install these 4 apps. The first 3 must all come from **F-Droid**, not the Play Store.

1. On your phone, open the web browser and go to **f-droid.org**.
2. Tap **Download F-Droid** and install it. If your phone warns you about
   installing apps from the browser, tap **Settings** and turn on
   **Allow from this source**, then go back.
3. Open the **F-Droid** app. Search for and install each of these:
   - **Termux**
   - **Termux:API**
   - **Termux:Tasker**
4. Open the **Google Play Store** and install **Tasker** (by joaomgcd).

---

## Part 2: Get your GroupMe token

Your token is like a password that lets this tool send messages as you.
**Never share it with anyone.**

1. On your phone's web browser, go to **dev.groupme.com**
2. Tap **Log In** and sign in with your normal GroupMe account.
3. At the top right of the page, tap **Access Token**.
   - Don't see it? In your browser menu (the 3 dots), turn on
     **Desktop site**, then look again.
4. A long string of letters and numbers appears. That is your token.
   Press and hold on it, then tap **Copy**.

Keep it copied. You will paste it in the next part.

> If you ever change your GroupMe password, your token changes too. See
> "Need a new token later?" at the end of Part 3.

---

## Part 3: Set up Termux (one copy and paste)

1. Open the **Termux** app. You will see a black screen with text. That is normal.
2. Copy this whole line:

   ```
   curl -fsSL https://raw.githubusercontent.com/peterchabs/groupme-voice-android/main/install.sh | bash
   ```

3. In Termux, press and hold on the black screen, tap **Paste**, then tap
   **Enter** on your keyboard.
4. Wait. It will download things for a minute or two.
   - If it asks **"Do you want to continue? [Y/n]"**, type **y** and tap Enter.
   - If your phone asks to let Termux access files, tap **Allow**.
5. When it says **"Paste your GroupMe token"**, press and hold on the screen,
   tap **Paste**, then tap **Enter**. (You won't see the token appear. That is
   on purpose.)
6. You should now see a list of your GroupMe groups. If you do, Termux is done.

**Need a new token later?** Copy a new one (Part 2). Then open Termux, paste
this line, tap Enter, and run the long line from step 2 again:

```
rm ~/.config/groupme-voice/token
```

---

## Part 4: Set up Tasker

### 4a. Let Tasker talk to Termux

1. Open your phone's **Settings** > **Apps** > **Tasker** > **Permissions**.
2. Tap **Additional permissions** (or "Other permissions").
3. Turn on **Run commands in Termux environment**.

### 4b. Build the task

Open **Tasker**. If it shows a welcome or tips screen, tap through it.

1. Tap the **TASKS** tab at the top.
2. Tap the **+** button at the bottom. Type the name **GroupMe Voice** and tap the check mark.

Now add 3 steps. For each one, tap the **+** at the bottom of the screen.

**Step 1: Listen to you**

1. Tap **+**, then **Input**, then **Get Voice**.
2. In **Prompt**, type: `Which group, and what should I say?`
3. Tap the back arrow at the top left to save.

**Step 2: Save what you said**

1. Tap **+**, then **File**, then **Write File**.
2. In **File**, type: `Tasker/groupme_heard.txt`
3. In **Text**, type: `%gv_heard`
4. Make sure **Append** is turned **off**.
5. Tap the back arrow to save.

**Step 3: Send it to GroupMe**

1. Tap **+**, then **Plugin**, then **Termux:Tasker**.
2. Tap the **pencil** icon next to Configuration.
3. In **Executable**, type: `groupme_send.py`
4. In **Arguments**, type: `--heard-file ~/storage/shared/Tasker/groupme_heard.txt`
5. Leave **Execute in a terminal session** unchecked.
6. Tap the check mark or back arrow to save, then the back arrow again.

**Test it:** tap the **play button** (triangle) at the bottom of the task. Say
the name of one of your groups, then "that says", then your message. For
example: **"Weekend Crew that says this is a test"**.

### 4c. Make "Hey Google" start it

1. In Tasker, tap the **3 dots** at the top right, then **Preferences**.
2. Tap the **Misc** tab.
3. Tap **App Shortcut Tasks** and pick **GroupMe Voice**.
4. Now say: **"Hey Google, open GroupMe Voice"**

If "Hey Google" doesn't start it, you can still use it with one tap:
press and hold your home screen, tap **Widgets**, find **Tasker**, drag the
**Task** widget onto your home screen, and pick **GroupMe Voice**.

---

## How to talk to it

When it asks "Which group, and what should I say?", say:

> **[group name]** that says **[your message]**

Examples:

- "Family that says I'm picking up pizza"
- "Weekend Crew that says running 10 minutes late"
- "Book club saying see you Thursday"

Tips:

- You only need part of the group name. "Crew" finds "Weekend Crew".
- If more than one group matches, it **sends nothing** and asks which one you meant.
- If it can't understand you, it **sends nothing**. It will never guess.

---

## Optional: nicknames for groups

If a group has a long or hard-to-say name, give it a nickname.

1. In Termux, paste this and tap Enter to see your groups and their numbers:

   ```
   ~/.termux/tasker/groupme_send.py --list
   ```

2. Then paste this, changing the nickname and number to yours:

   ```
   echo '{"family": "12345678", "work": "87654321"}' > ~/.config/groupme-voice/aliases.json
   ```

Now "family that says hello" always goes to group 12345678.

---

## Something not working?

| What happens | What to do |
|---|---|
| "GroupMe rejected the token" | Your token changed. See "Need a new token later?" in Part 3. |
| Nothing happens when the task runs | Check Part 4a: Tasker needs the Termux permission. |
| "I didn't catch a message" | Speak right after the beep, and say "that says" between the group and the message. |
| "I couldn't find a group called ..." | Say more of the group name, or set up a nickname. |
| No voice reply, but the message sent | Make sure the **Termux:API** app is installed from F-Droid. |
| Termux says "permission denied" for storage | In Termux, paste `termux-setup-storage`, tap Enter, then tap Allow. |

---

## Is this safe?

- Your token stays on your phone, in a file only Termux can read.
- Nothing is sent anywhere except to GroupMe.
- It can only post to groups you're already in, as you.
- The code is all in [`groupme_send.py`](groupme_send.py) if you want to look.

This project is not made by or connected to GroupMe or Microsoft.
