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
 * Lets Gemini (and other system assistants on Android 16+) send a GroupMe message through Say So
 * in one request, like "Hey Google, use Say So to tell Weekend Crew I'm running late".
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
                    return@withContext SentGroupMeMessage(to = group.name, message = text, direct = false)
                }
                val person = findPerson(token, params.recipient)
                Api.sendDm(token, person.id, text)
                SentGroupMeMessage(to = person.name, message = text, direct = true)
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
    val to: String,
    /** The message that was sent. */
    val message: String,
    /** True if it went to one person directly instead of a group. */
    val direct: Boolean,
)
