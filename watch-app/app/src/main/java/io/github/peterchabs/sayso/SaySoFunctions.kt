package io.github.peterchabs.sayso

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Lets Gemini (and other system assistants on Android 16+) send and read GroupMe messages through
 * Say So, like "Hey Google, use Say So to tell Weekend Crew I'm running late" or "what did Sam say
 * in Say So?" and then an answer. Gemini on a watch acts through the paired phone, which carries
 * these too, so a Wear OS 6 watch (no AppFunctions of its own) still gets them.
 * It uses the same group matching as speaking to Say So, and never guesses between two groups.
 * Only the phone's system assistant can call these; other apps can't.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "SaySoAppFunctionService",
    appFunctionXmlFileName = "sayso_app_functions",
)
abstract class SaySoFunctions : AppFunctionService() {

    /**
     * Sends a GroupMe message from the user's own GroupMe account to one of their groups, or to a
     * person they already have direct messages with.
     *
     * @param params Who to send to and what to say.
     * @return Where the message went and what it said.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun sendGroupMeMessage(params: SendGroupMeMessageParams): SentGroupMeMessage =
        withContext(Dispatchers.IO) {
            val text = params.message.trim()
            if (text.isEmpty()) throw AppFunctionInvalidArgumentException("There's no message to send.")
            if (text.length > 1000) throw AppFunctionInvalidArgumentException("GroupMe allows 1000 characters.")
            val token = Store.token(applicationContext)
            if (token.isEmpty()) throw AppFunctionDeniedException("Open Say So and sign in to GroupMe first.")
            try {
                val group = findGroup(token, params.recipient)
                if (group != null) {
                    Api.send(token, group.id, text)
                    return@withContext SentGroupMeMessage(sentTo = group.name, message = text, direct = false)
                }
                val person = findPerson(token, params.recipient)
                Api.sendDm(token, person.id, text)
                SentGroupMeMessage(sentTo = person.name, message = text, direct = true)
            } catch (e: Api.AuthException) {
                Store.forgetSignIn(applicationContext)
                throw AppFunctionDeniedException("GroupMe signed you out. Open Say So and sign in again.")
            } catch (e: IOException) {
                throw AppFunctionAppUnknownException(e.message ?: "Couldn't reach GroupMe. Nothing was sent.")
            }
        }

    /**
     * Lists the names of the user's GroupMe groups, so you can pick the right one to message.
     *
     * @return The group names, alphabetically.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listGroupMeGroups(): List<String> = withContext(Dispatchers.IO) {
        val token = Store.token(applicationContext)
        if (token.isEmpty()) throw AppFunctionDeniedException("Open Say So and sign in to GroupMe first.")
        try {
            groups(token, fresh = true).map { it.name }.sortedBy { it.lowercase() }
        } catch (e: Api.AuthException) {
            Store.forgetSignIn(applicationContext)
            throw AppFunctionDeniedException("GroupMe signed you out. Open Say So and sign in again.")
        }
    }

    /**
     * Reads the user's newest GroupMe messages so you can tell them what was said and help them
     * answer (send the answer with sendGroupMeMessage). With no chat named, it gives the newest
     * message from each of their most recently active chats; with a chat named (a group, or a
     * person they have direct messages with), the last few messages in that chat.
     *
     * @param params Which chat to read (optional) and how many messages.
     * @return The messages, oldest first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun readGroupMeMessages(params: ReadGroupMeMessagesParams): List<GroupMeMessage> =
        withContext(Dispatchers.IO) {
            val token = Store.token(applicationContext)
            if (token.isEmpty()) throw AppFunctionDeniedException("Open Say So and sign in to GroupMe first.")
            val count = (params.count ?: 5).coerceIn(1, 20)
            val myId = Store.myId(applicationContext)
            val now = System.currentTimeMillis()
            try {
                val chats = Feed.conversations(token).sortedByDescending { it.lastAt }
                val wanted = params.chat?.trim().orEmpty()
                if (wanted.isEmpty()) {
                    return@withContext chats.filter { it.lastAt > 0 }.take(count).mapNotNull { c ->
                        Feed.messages(token, c, 3).lastOrNull { !it.system }?.let { toMessage(c, it, myId, now) }
                    }.reversed()
                }
                val named = chats.map { Phrase.Group(it.id + (if (it.isDm) ":dm" else ":g"), it.name) }
                val m = Phrase.match(named, wanted)
                val hit = m.group ?: throw AppFunctionElementNotFoundException(
                    if (m.problem.isNotEmpty()) m.problem else "No GroupMe chat called \"$wanted\".")
                val c = chats.first { it.id + (if (it.isDm) ":dm" else ":g") == hit.id }
                Feed.messages(token, c, count).filter { !it.system }.sortedBy { it.at }.takeLast(count)
                    .map { toMessage(c, it, myId, now) }
            } catch (e: Api.AuthException) {
                Store.forgetSignIn(applicationContext)
                throw AppFunctionDeniedException("GroupMe signed you out. Open Say So and sign in again.")
            } catch (e: IOException) {
                throw AppFunctionAppUnknownException(e.message ?: "Couldn't reach GroupMe.")
            }
        }

    private fun toMessage(c: Feed.Convo, m: Feed.Msg, myId: String, now: Long): GroupMeMessage {
        val text = when {
            m.text.isNotBlank() && m.media.isNotEmpty() -> m.text.trim() + " (with a picture)"
            m.text.isNotBlank() -> m.text.trim()
            m.media.isNotEmpty() -> "(a picture)"
            else -> "(no text)"
        }
        return GroupMeMessage(
            chat = c.name, sender = m.sender, message = text,
            time = Feed.stamp(m.at, now), direct = c.isDm, fromUser = m.senderId == myId)
    }

    private fun groups(token: String, fresh: Boolean): List<Phrase.Group> {
        if (!fresh) {
            val cached = Store.groups(applicationContext)
            if (cached.isNotEmpty() && Store.groupsAgeMs(applicationContext) < CACHE_MS) return cached
        }
        return Api.listGroups(token).also { Store.saveGroups(applicationContext, it) }
    }

    /** The group named, or null if it should be tried as a person. Throws when it's ambiguous. */
    private fun findGroup(token: String, name: String): Phrase.Group? {
        var m = Phrase.match(groups(token, fresh = false), name)
        if (m.group == null) m = Phrase.match(groups(token, fresh = true), name)
        if (m.group != null) return m.group
        if (m.problem.startsWith("Which one") || m.problem.startsWith("You have")) {
            throw AppFunctionInvalidArgumentException(m.problem + " Nothing was sent.")
        }
        return null
    }

    private fun findPerson(token: String, name: String): Phrase.Group {
        var people = Store.contacts(applicationContext)
        var m = Phrase.match(people, name)
        if (m.group == null) {
            people = Api.listContacts(token).also { Store.saveContacts(applicationContext, it) }
            m = Phrase.match(people, name)
        }
        return m.group ?: throw AppFunctionElementNotFoundException(
            "No GroupMe group or person called \"$name\". Nothing was sent.")
    }

    private companion object {
        const val CACHE_MS = 6 * 60 * 60 * 1000L
    }
}

/** Who to send to and what to say. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SendGroupMeMessageParams(
    /** The GroupMe group's name, or the name of a person the user has direct messages with. */
    val recipient: String,
    /** The exact message to send, in the user's words. */
    val message: String,
)

/** A message that was sent. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SentGroupMeMessage(
    /** The group or person it was sent to. */
    val sentTo: String,
    /** The message that was sent. */
    val message: String,
    /** True if it went to one person directly instead of a group. */
    val direct: Boolean,
)

/** Which messages to read. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ReadGroupMeMessagesParams(
    /** A group's name, or a person the user has direct messages with. Leave it out for the newest messages across all chats. */
    val chat: String?,
    /** How many messages, 1 to 20 (5 when left out). */
    val count: Int?,
)

/** A GroupMe message. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class GroupMeMessage(
    /** The group, or the person for a direct message. */
    val chat: String,
    /** Who sent it. */
    val sender: String,
    /** What it says. */
    val message: String,
    /** When it was sent, like "5:34 PM" or "Yesterday 5:34 PM". */
    val time: String,
    /** True for a direct message. */
    val direct: Boolean,
    /** True if the user sent it themselves. */
    val fromUser: Boolean,
)
