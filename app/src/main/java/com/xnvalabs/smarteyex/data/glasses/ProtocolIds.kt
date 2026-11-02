// Dihasilkan oleh tools/gen_protocol.py dari docs/protocol/smarteyex-ble-v1.json. Jangan diedit manual.
package com.xnvalabs.smarteyex.data.glasses

object ProtocolIds {
    const val SERVICE_UUID = "caee0001-0087-43a0-82ed-500a709a9129"
    const val UUID_DEVICE_INFO = "caee0002-0087-43a0-82ed-500a709a9129"
    const val UUID_STATUS = "caee0003-0087-43a0-82ed-500a709a9129"
    const val UUID_AUTH = "caee0004-0087-43a0-82ed-500a709a9129"
    const val UUID_CONTROL = "caee0005-0087-43a0-82ed-500a709a9129"
    const val UUID_EVENT = "caee0006-0087-43a0-82ed-500a709a9129"
    const val UUID_IMAGE = "caee0007-0087-43a0-82ed-500a709a9129"
    const val UUID_AUDIO_IN = "caee0008-0087-43a0-82ed-500a709a9129"
    const val UUID_AUDIO_OUT = "caee0009-0087-43a0-82ed-500a709a9129"
    const val MAC_LABEL_GLASSES = "SEX1-G"
    const val MAC_LABEL_PHONE = "SEX1-C"
    object Cmd {
        const val CAPTURE = 1
        const val MIC_START = 2
        const val MIC_STOP = 3
        const val SPEAK_BEGIN = 4
        const val SPEAK_END = 5
        const val SPEAK_ABORT = 6
        const val SET_VOLUME = 7
        const val PING = 8
        const val TONE = 9
    }
    object Evt {
        const val BUTTON = 1
        const val CAPTURE_READY = 2
        const val CAPTURE_FAILED = 3
        const val MIC_STARTED = 4
        const val MIC_ENDED = 5
        const val CMD_ACK = 6
        const val LOW_BATTERY = 7
        const val SPEAK_UNDERRUN = 8
        const val PRIVACY = 9
        const val ERROR = 10
    }
    object St {
        const val OK = 0
        const val BAD_COMMAND = 1
        const val BAD_PARAMS = 2
        const val BUSY = 3
        const val NOT_AUTHENTICATED = 4
        const val UNSUPPORTED = 5
        const val PRIVACY_BLOCKED = 6
        const val HW_FAILURE = 7
        const val LOW_BATTERY = 8
        const val MTU_TOO_SMALL = 9
    }
    object Auth {
        const val HELLO = 1
        const val PROOF = 2
        const val PROVISION = 16
        const val FORGET = 17
        const val CHALLENGE = 129
        const val AUTH_OK = 130
        const val AUTH_FAIL = 131
        const val PROVISION_OK = 144
        const val FORGET_OK = 145
        const val PROVISION_FAIL = 147
    }
    object Authfail {
        const val NO_SECRET = 1
        const val BAD_MAC = 2
        const val LOCKED_OUT = 3
        const val BAD_STATE = 4
        const val MTU_TOO_SMALL = 5
    }
    object Provfail {
        const val NOT_PAIRING = 1
        const val STORAGE = 3
    }
    object Btn {
        const val SHORT = 1
        const val LONG = 2
        const val DOUBLE = 3
        const val HOLD_START = 4
        const val HOLD_END = 5
    }
    object Micend {
        const val STOPPED = 1
        const val TIMEOUT = 2
        const val BUTTON_RELEASED = 3
        const val ERROR = 4
    }
    object Capfail {
        const val PRIVACY = 1
        const val BUSY = 2
        const val CAMERA_INIT = 3
        const val NO_FRAME = 4
        const val TRANSFER = 5
    }
    object Flag {
        const val CAMERA_ACTIVE = 1
        const val MIC_ACTIVE = 2
        const val SPEAKER_ACTIVE = 4
        const val AUTHENTICATED = 8
        const val PAIRING_MODE = 16
        const val LOW_BATTERY = 32
        const val PRIVACY_CLOSED = 64
        const val PROVISIONED = 128
    }
    object Charge {
        const val DISCHARGING = 0
        const val CHARGING = 1
        const val FULL = 2
        const val UNKNOWN = 255
    }
    object Res {
        const val QVGA = 0
        const val VGA = 1
        const val SVGA = 2
        const val XGA = 3
    }
    object K {
        const val SECRET_LEN = 32
        const val NONCE_LEN = 16
        const val MAC_LEN = 32
        const val MIN_MTU = 185
        const val MAX_MTU = 247
        const val AUDIO_RATE_HZ = 16000
        const val AUDIO_FRAME_SAMPLES = 320
        const val AUDIO_HEADER_LEN = 6
        const val IMAGE_HEADER_LEN = 5
        const val STATUS_LEN = 10
        const val PROTO_VERSION = 1
        const val PAIRING_WINDOW_MS = 60000
        const val AUTH_MAX_FAILS = 5
        const val AUTH_LOCKOUT_MS = 60000
    }
}
