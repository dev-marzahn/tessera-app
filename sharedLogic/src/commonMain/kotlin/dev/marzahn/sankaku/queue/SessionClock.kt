package dev.marzahn.sankaku.queue

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/** Which session a queue item joined the queue in (Glossary → Session). Opaque; saved in `tabs.json`. */
@JvmInline
value class SessionId(val value: String) {
  companion object {
    /**
     * The one session every tab of a `tabs.json` from before sessions joins (the v1 migration): older than any other,
     * so the items opened "later" go after them.
     */
    val EARLIER = SessionId("earlier")

    fun random() = SessionId(Uuid.random().toString())
  }
}

/**
 * The current session, and where one ends. "Later" puts a new item at the end of the [current] session's items.
 *
 * A session ends only when the user chooses Clear viewed, which calls [startNew]. Closing the app never ends one:
 * [atLaunch] carries on in the saved session.
 */
class SessionClock private constructor(current: SessionId) {
  var current: SessionId = current
    private set

  /** Ends the current session and starts another; returns it. */
  fun startNew(): SessionId = SessionId.random().also { current = it }

  companion object {
    /**
     * The session to run in after a launch that found [saved]: the saved one. With nothing saved (a first launch, or a
     * file from before sessions) there is no session to carry on, so a new one starts.
     */
    fun atLaunch(saved: SessionId?): SessionClock = SessionClock(saved ?: SessionId.random())

    /** A clock on [session], for tests and for restoring one. */
    fun of(session: SessionId): SessionClock = SessionClock(session)
  }
}
