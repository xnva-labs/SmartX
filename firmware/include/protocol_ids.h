// Dihasilkan oleh tools/gen_protocol.py dari docs/protocol/smarteyex-ble-v1.json. Jangan diedit manual.
#pragma once
#include <stdint.h>

namespace sex {
constexpr const char* SERVICE_UUID = "caee0001-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_DEVICE_INFO = "caee0002-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_STATUS = "caee0003-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_AUTH = "caee0004-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_CONTROL = "caee0005-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_EVENT = "caee0006-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_IMAGE = "caee0007-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_AUDIO_IN = "caee0008-0087-43a0-82ed-500a709a9129";
constexpr const char* UUID_AUDIO_OUT = "caee0009-0087-43a0-82ed-500a709a9129";
constexpr const char* MAC_LABEL_GLASSES = "SEX1-G";
constexpr const char* MAC_LABEL_PHONE = "SEX1-C";
namespace cmd {
constexpr uint32_t CAPTURE = 1u;
constexpr uint32_t MIC_START = 2u;
constexpr uint32_t MIC_STOP = 3u;
constexpr uint32_t SPEAK_BEGIN = 4u;
constexpr uint32_t SPEAK_END = 5u;
constexpr uint32_t SPEAK_ABORT = 6u;
constexpr uint32_t SET_VOLUME = 7u;
constexpr uint32_t PING = 8u;
constexpr uint32_t TONE = 9u;
}
namespace evt {
constexpr uint32_t BUTTON = 1u;
constexpr uint32_t CAPTURE_READY = 2u;
constexpr uint32_t CAPTURE_FAILED = 3u;
constexpr uint32_t MIC_STARTED = 4u;
constexpr uint32_t MIC_ENDED = 5u;
constexpr uint32_t CMD_ACK = 6u;
constexpr uint32_t LOW_BATTERY = 7u;
constexpr uint32_t SPEAK_UNDERRUN = 8u;
constexpr uint32_t PRIVACY = 9u;
constexpr uint32_t ERROR = 10u;
}
namespace st {
constexpr uint32_t OK = 0u;
constexpr uint32_t BAD_COMMAND = 1u;
constexpr uint32_t BAD_PARAMS = 2u;
constexpr uint32_t BUSY = 3u;
constexpr uint32_t NOT_AUTHENTICATED = 4u;
constexpr uint32_t UNSUPPORTED = 5u;
constexpr uint32_t PRIVACY_BLOCKED = 6u;
constexpr uint32_t HW_FAILURE = 7u;
constexpr uint32_t LOW_BATTERY = 8u;
constexpr uint32_t MTU_TOO_SMALL = 9u;
}
namespace auth {
constexpr uint32_t HELLO = 1u;
constexpr uint32_t PROOF = 2u;
constexpr uint32_t PROVISION = 16u;
constexpr uint32_t FORGET = 17u;
constexpr uint32_t CHALLENGE = 129u;
constexpr uint32_t AUTH_OK = 130u;
constexpr uint32_t AUTH_FAIL = 131u;
constexpr uint32_t PROVISION_OK = 144u;
constexpr uint32_t FORGET_OK = 145u;
constexpr uint32_t PROVISION_FAIL = 147u;
}
namespace authfail {
constexpr uint32_t NO_SECRET = 1u;
constexpr uint32_t BAD_MAC = 2u;
constexpr uint32_t LOCKED_OUT = 3u;
constexpr uint32_t BAD_STATE = 4u;
constexpr uint32_t MTU_TOO_SMALL = 5u;
}
namespace provfail {
constexpr uint32_t NOT_PAIRING = 1u;
constexpr uint32_t STORAGE = 3u;
}
namespace btn {
constexpr uint32_t SHORT = 1u;
constexpr uint32_t LONG = 2u;
constexpr uint32_t DOUBLE = 3u;
constexpr uint32_t HOLD_START = 4u;
constexpr uint32_t HOLD_END = 5u;
}
namespace micend {
constexpr uint32_t STOPPED = 1u;
constexpr uint32_t TIMEOUT = 2u;
constexpr uint32_t BUTTON_RELEASED = 3u;
constexpr uint32_t ERROR = 4u;
}
namespace capfail {
constexpr uint32_t PRIVACY = 1u;
constexpr uint32_t BUSY = 2u;
constexpr uint32_t CAMERA_INIT = 3u;
constexpr uint32_t NO_FRAME = 4u;
constexpr uint32_t TRANSFER = 5u;
}
namespace flag {
constexpr uint32_t CAMERA_ACTIVE = 1u;
constexpr uint32_t MIC_ACTIVE = 2u;
constexpr uint32_t SPEAKER_ACTIVE = 4u;
constexpr uint32_t AUTHENTICATED = 8u;
constexpr uint32_t PAIRING_MODE = 16u;
constexpr uint32_t LOW_BATTERY = 32u;
constexpr uint32_t PRIVACY_CLOSED = 64u;
constexpr uint32_t PROVISIONED = 128u;
}
namespace charge {
constexpr uint32_t DISCHARGING = 0u;
constexpr uint32_t CHARGING = 1u;
constexpr uint32_t FULL = 2u;
constexpr uint32_t UNKNOWN = 255u;
}
namespace res {
constexpr uint32_t QVGA = 0u;
constexpr uint32_t VGA = 1u;
constexpr uint32_t SVGA = 2u;
constexpr uint32_t XGA = 3u;
}
namespace k {
constexpr uint32_t SECRET_LEN = 32u;
constexpr uint32_t NONCE_LEN = 16u;
constexpr uint32_t MAC_LEN = 32u;
constexpr uint32_t MIN_MTU = 185u;
constexpr uint32_t MAX_MTU = 247u;
constexpr uint32_t AUDIO_RATE_HZ = 16000u;
constexpr uint32_t AUDIO_FRAME_SAMPLES = 320u;
constexpr uint32_t AUDIO_HEADER_LEN = 6u;
constexpr uint32_t IMAGE_HEADER_LEN = 5u;
constexpr uint32_t STATUS_LEN = 10u;
constexpr uint32_t PROTO_VERSION = 1u;
constexpr uint32_t PAIRING_WINDOW_MS = 60000u;
constexpr uint32_t AUTH_MAX_FAILS = 5u;
constexpr uint32_t AUTH_LOCKOUT_MS = 60000u;
}
}
