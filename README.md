# GroupMe Voice

Send a GroupMe message just by talking to your **Galaxy Watch** or **Android phone**.

You say: **"Hey Google, open GroupMe Voice"**
It listens, and your words appear on screen as you talk:
**"Weekend Crew that says I'm running 10 minutes late"**
It shows who it's sending to, counts down 3 seconds (so you can cancel), then sends.

The message goes out from **your own GroupMe account**, to **your own groups**,
with **whatever words you say each time**. You don't need to know how to code.

## Pick one

| I want to talk to... | Use | Cost | Time |
|---|---|---|---|
| **My Galaxy Watch** | [The watch app](#galaxy-watch-app) | Free | about 20 minutes, once |
| **My Android phone** | [The phone setup](#phone-setup) | About $3.50 (Tasker) | about 15 minutes, once |

You can set up both. They don't need each other.

---

## Galaxy Watch app

A small app that lives on your watch and talks to GroupMe by itself.

### What you need

- A **Galaxy Watch 4 or newer** (or any watch with Wear OS 3 or newer).
- **A computer** (Windows or Mac), only for installing the app one time.
- Your computer and your watch on the **same Wi-Fi**.
- **A phone** (any kind, even an iPhone) to sign in to GroupMe.
- Your GroupMe email or phone number and password.

You only do Steps 1 to 5 once. After that it's just "Hey Google".

---

### Step 1: Download the files to your computer

You need two downloads. Both are free.

**A. The watch app**

1. On your computer, open this link:
   **https://github.com/peterchabs/groupme-voice-android/releases/latest**
2. Scroll down to the **Assets** section.
3. Click **GroupMeVoice-watch.apk**. It saves to your **Downloads** folder.

> Don't use the green **Code** button or **Download ZIP** on the main page.
> That's the source code, not the app. You only need the one file above.

**B. Google's free install tool ("Platform Tools")**

1. Download the ZIP for your computer:
   - **Windows:** https://dl.google.com/android/repository/platform-tools-latest-windows.zip
   - **Mac:** https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
2. Open your **Downloads** folder and unzip it:
   - **Windows:** right-click the ZIP, click **Extract All**, then **Extract**.
   - **Mac:** double-click the ZIP.
3. You now have a folder called **platform-tools**.
4. **Move the watch app into that folder:** drag **GroupMeVoice-watch.apk**
   into the **platform-tools** folder.

---

### Step 2: Turn on "install apps" mode on your watch

1. On your watch, open **Settings**.
2. Scroll down and tap **About watch**, then **Software information**.
3. Tap **Software version** about 7 times, until a message says developer mode is on.
4. Go back to the main **Settings** screen. Scroll to the bottom and tap the
   new **Developer options**.
5. Turn on **ADB debugging**. Tap **OK** if asked.
6. Make sure your watch is on Wi-Fi: **Settings** > **Connections** > **Wi-Fi**,
   and pick the **same Wi-Fi network** your computer is on.
7. Back in **Developer options**, tap **Wireless debugging** and turn it on.
   Tap **Allow** if asked.

Keep your watch screen on the **Wireless debugging** page. You'll need it next.

---

### Step 3: Open a command window inside the platform-tools folder

**Windows**

1. Open the **platform-tools** folder.
2. Click once in the **address bar** at the top of the window (where it shows the folder path).
3. Type `cmd` and press **Enter**. A black window opens. That's the command window.

**Mac**

1. Open **Terminal** (press **Command + Space**, type `Terminal`, press **Enter**).
2. Type `cd ` (the letters c, d, and a space). Don't press Enter yet.
3. Drag the **platform-tools** folder into the Terminal window, then press **Enter**.

> **Mac users:** in the commands below, type `./adb` instead of `adb`.

---

### Step 4: Install the app on your watch

**4a. Pair your computer with your watch**

1. On your watch, on the **Wireless debugging** page, tap **Pair new device**.
   It shows a **6-digit code** and an address like `192.168.1.23:41235`.
2. In the command window, type `adb pair ` followed by **that address**, then press **Enter**. Example:

   ```
   adb pair 192.168.1.23:41235
   ```

3. When it asks for the pairing code, type the **6-digit code** from your watch and press **Enter**.
4. You should see **"Successfully paired"**.

**4b. Connect**

1. On your watch, go back one screen to the main **Wireless debugging** page.
   Under **IP address & port** it shows another address (the last number is different
   from the pairing one).
2. Type `adb connect ` followed by **that address**, then press **Enter**. Example:

   ```
   adb connect 192.168.1.23:38741
   ```

3. You should see **"connected to ..."**. If your watch asks **Allow debugging?**, tap **Allow**.

**4c. Install**

1. Type this and press **Enter**:

   ```
   adb install GroupMeVoice-watch.apk
   ```

2. Wait until it says **Success**. The app is now on your watch.

**4d. Turn install mode back off (recommended)**

On your watch, go to **Settings** > **Developer options** and turn off
**Wireless debugging** and **ADB debugging**. The app stays installed.

---

### Step 5: Sign in to GroupMe

1. On your watch, open your apps list and tap **GroupMe Voice** (the blue microphone).
2. The watch shows a **QR code** and a **10-character code** like `K7Q2M-9XAPZ`.
3. On your phone, open the **camera** and point it at the QR code on your watch.
   Tap the link that pops up.
   - Camera won't scan it? On your phone, go to
     **peterchabs.github.io/groupme-voice-android** and type the code from your watch.
4. The page walks you through it:
   1. Tap **Open GroupMe** and log in with your normal GroupMe account.
   2. At the top right of that page, tap **Access Token**. (Don't see it? In your
      browser menu, turn on **Desktop site**.)
   3. Copy the long code it shows. This is your **token**. It works like a password,
      so never share it with anyone.
   4. Go back to the first tab, tap **Paste**, then tap **Send to my watch**.
5. Within a few seconds your watch says **"Hi, [your name]!"** and how many groups it found.

That's it. You're set up.

---

### Step 6: Use it

1. Say **"Hey Google, open GroupMe Voice"** to your watch.
   (Or just tap the app.)
2. The first time only, it asks to use the microphone. Tap **Allow**.
3. When the circle starts pulsing, say:

   > **[group name]** that says **[your message]**

   For example: **"Family that says I'm picking up pizza"**.
4. Your words appear as you speak. Then it shows the group and your message,
   and a ring counts down **3 seconds**.
   - Heard you wrong? Tap **Cancel**. Nothing is sent.
   - In a hurry? Tap **Send now**.
5. A green check and a buzz mean it's **sent**.

**What the screen shows:**

| You see | It means |
|---|---|
| Blue circle with rings that grow when you talk | Listening |
| Spinning ring | Working on it |
| Ring that shrinks to nothing | Sending in a moment (tap Cancel to stop) |
| Green circle with a check | Sent |
| Red circle that shakes | Something's wrong. The words under it say what, and nothing was sent |

**Tips**

- You only need part of the group name. "Crew" finds "Weekend Crew".
  If two groups match, it asks which one and **sends nothing**. It never guesses.
- **"Hey Google" doesn't work?** Many Galaxy Watches come set to Bixby.
  Try **"Hi Bixby, open GroupMe Voice"**, or install **Google Assistant** (or
  **Gemini**) from the Play Store on your watch and make it your assistant.
- **Want a button for it?** On many Galaxy Watches: **Settings** > **Advanced features** >
  **Customize buttons** > **Double press** > pick **GroupMe Voice**.
- Your watch needs internet, either through your phone (Bluetooth), Wi-Fi, or LTE.
- To switch accounts or sign out, tap **Settings** in the app.

---

### Watch: something not working?

| What happens | What to do |
|---|---|
| **"adb is not recognized"** or **"command not found"** | Your command window isn't in the platform-tools folder. Redo Step 3. On a Mac, type `./adb`, not `adb`. |
| **"failed to connect"** or pairing fails | Check the watch and computer are on the same Wi-Fi. The addresses change each time you turn on Wireless debugging, so read them off the watch again. |
| **"No such file"** when installing | The app file isn't in the platform-tools folder. Drag **GroupMeVoice-watch.apk** in there (Step 1, part B, step 4). |
| The watch says **"That code expired"** | Codes last 10 minutes. Tap **Get a new code** and scan again. |
| The page says **"That doesn't look like a GroupMe token"** | Copy the token again from dev.groupme.com. It's one long string of letters and numbers. |
| **"Please sign in again"** | Your token changed (this happens if you change your GroupMe password). Tap **Sign in** and do Step 5 again. |
| It can't find your group | Say more of the group's name, a little slower. |

---

## Phone setup

The steps below are for talking to your **phone** (Parts 1 to 4). Skip them if
you only want the watch app.

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

- **Phone setup:** your token stays on your phone, in a file only Termux can read.
- **Watch app:** your token is stored only inside the app on your watch. When you
  sign in, the web page locks (encrypts) your token with the code shown on your
  watch before sending it, and passes it through a free relay service
  ([ntfy.sh](https://ntfy.sh)) that can't unlock it. The code never leaves your
  phone and watch, and it expires after 10 minutes.
- Your messages go only to GroupMe.
- It can only post to groups you're already in, as you.
- All the code is here if you want to look: [`groupme_send.py`](groupme_send.py)
  (phone), [`watch-app/`](watch-app/) (watch), [`docs/index.html`](docs/index.html) (sign-in page).

This project is not made by or connected to GroupMe or Microsoft.
