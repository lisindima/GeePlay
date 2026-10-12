package com.kooo.evcc.evccapi

/** Stable identifiers shared by EVCC, SDK clients, samples and generated documentation. */
object EvccApiContract {
    const val API_MAJOR = 1
    const val API_MINOR = 2
    const val CONTRACT_HASH = "evccapi-1.2"

    const val EVCC_PACKAGE = "com.kooo.evcc"
    const val ACTION_BIND = "com.kooo.evcc.evccapi.BIND"
    const val ACTION_OPEN_SETTINGS = "com.kooo.evcc.evccapi.OPEN_SETTINGS"
    const val ACCESS_PERMISSION = "com.kooo.evcc.permission.ACCESS_EVCC_API"
    const val MAX_BINDER_PAYLOAD_BYTES = 512 * 1024
    const val DEFAULT_PAGE_SIZE = 100
    const val MAX_PAGE_SIZE = 500
    const val MAX_BATCH_SIZE = 100
    const val MAX_PROPERTY_SUBSCRIPTIONS = 256
    const val MAX_VARIABLE_SUBSCRIPTIONS = 256
    const val MAX_WRITE_VALUE_CHARS = 16 * 1024

    const val MAX_MUSIC_RESOURCE_BYTES = 8 * 1024 * 1024

    object Source {
        const val AUTO = "AUTO"
        const val VHAL = "VHAL"
        const val APVP = "APVP"
        const val VEHICLE_AIDL = "VEHICLE_AIDL"
        const val CAPI = "CAPI"
        const val ADAPT_API = "ADAPT_API"
        const val SOA = "SOA"
        const val GEELY_AIDL = "GEELY_AIDL"
        const val GEELY_CAPI = "GEELY_CAPI"
    }

    object ResolutionMode {
        const val AUTO_PRIORITY = "AUTO_PRIORITY"
        const val STRICT_UNIQUE = "STRICT_UNIQUE"
        const val ALL_MATCHES = "ALL_MATCHES"
    }

    object Status {
        const val STALE_RESOURCE = "STALE_RESOURCE"
        const val OK = "OK"
        const val ACCEPTED = "ACCEPTED"
        const val PENDING = "PENDING"
        const val AVAILABLE = "AVAILABLE"
        const val UNAVAILABLE = "UNAVAILABLE"
        const val TIMEOUT = "TIMEOUT"
        const val UNSUPPORTED = "UNSUPPORTED"
        const val NOT_FOUND = "NOT_FOUND"
        const val AMBIGUOUS_SOURCE = "AMBIGUOUS_SOURCE"
        const val AMBIGUOUS_AREA = "AMBIGUOUS_AREA"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
        const val SERVICE_DISABLED = "SERVICE_DISABLED"
        const val SESSION_CLOSED = "SESSION_CLOSED"
        const val CANCELLED = "CANCELLED"
        const val FAILED = "FAILED"
    }

    object EventType {
        const val INITIAL_SNAPSHOT = "INITIAL_SNAPSHOT"
        const val VALUE_CHANGED = "VALUE_CHANGED"
        const val SOURCE_CHANGED = "SOURCE_CHANGED"
        const val REMOVED = "REMOVED"
    }

    object Permission {
        const val VOLUME_READ = "volume.read"
        const val VOLUME_CONTROL = "volume.control"
        const val MUSIC_READ = "music.read"
        const val MUSIC_LISTEN = "music.listen"
        const val MUSIC_CONTROL = "music.control"
        const val PROPERTY_DIRECTORY = "property.directory"
        const val PROPERTY_READ = "property.read"
        const val PROPERTY_LISTEN = "property.listen"
        const val PROPERTY_WRITE = "property.write"
        const val PROPERTY_FULL_SCAN = "property.full_scan"
        const val VARIABLE_READ = "variable.read"
        const val VARIABLE_LISTEN = "variable.listen"
        const val VARIABLE_WRITE = "variable.write"
        const val VARIABLE_DELETE = "variable.delete"
        const val ACTION_SAFE = "action.safe"
        const val ACTION_PRIVILEGED = "action.privileged"
        const val SYSTEM_SETTINGS = "system.settings"
        const val SHELL = "shell.exec"
        const val SYSTEM_WRITE = "shell.system_write"
    }

    object Preset {
        const val READ_ONLY = "READ_ONLY"
        const val STANDARD = "STANDARD"
        const val ADVANCED = "ADVANCED"
        const val CUSTOM = "CUSTOM"
    }

    object DirectoryMode {
        const val DIRECTORY = "DIRECTORY"
        const val LIVE_FULL_SCAN = "LIVE_FULL_SCAN"
    }

    object ValueType {
        const val NULL = "NULL"
        const val STRING = "STRING"
        const val LONG = "LONG"
        const val DOUBLE = "DOUBLE"
        const val BOOLEAN = "BOOLEAN"
        const val JSON = "JSON"
        const val BYTES_BASE64 = "BYTES_BASE64"
    }
}
