package dev.nymph.lyrics.model.status

/** Why no answer arrived at all. */
enum class UnreachableReason {
    /** The device has no network (the system said so). */
    OFFLINE,

    /** Connection refused or reset, DNS failure: the device or the server host is unreachable. */
    NETWORK,

    /** The request timed out. */
    TIMEOUT,
}

/** Why the server answered but the backend is not healthy. */
sealed interface DownCause {
    /** This HTTP status: 502/503/504 from a regular call, any non-200 from `/api/health`. */
    data class Http(val status: Int) : DownCause

    /** `/api/health` answered 200 but not `{"status":"ok"}` (for example the SPA's index.html). */
    data object InvalidBody : DownCause
}

/** A problem worth telling the user about. */
sealed interface Outage {
    /** Something answered, but not a healthy backend (nginx with a dead upstream answers 502/504). */
    data class BackendDown(val cause: DownCause) : Outage

    /** Nothing answered. */
    data class Unreachable(val reason: UnreachableReason) : Outage
}

/** One observation of the backend, from a health probe or from a regular API call. */
sealed interface HealthSignal {
    /** The backend answered (any status a live backend produces). */
    data object Up : HealthSignal

    data class Failed(val outage: Outage) : HealthSignal
}
