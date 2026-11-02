#pragma once
#include <stddef.h>
#include <stdint.h>

// CRC-32 (IEEE 802.3, reflected, poly 0xEDB88320) - identical to zlib/java.util.zip.CRC32.
uint32_t crc32_update(uint32_t crc, const uint8_t* data, size_t len);
inline uint32_t crc32_of(const uint8_t* data, size_t len) { return crc32_update(0, data, len); }
