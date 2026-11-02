#pragma once
#include <stddef.h>
#include <stdint.h>

namespace speaker {
void begin();                                   // creates the playback task
bool begin_stream(uint8_t stream_id);           // opens the I2S output and clears the jitter buffer
void push_frame(const uint8_t* frame, size_t len);  // decodes one protocol audio frame into the jitter buffer
void end_stream();                              // play what is buffered, then stop
void abort_stream();                            // stop immediately
void set_volume(uint8_t pct);
bool tone(uint16_t freq_hz, uint16_t ms, uint8_t volume_pct);
bool active();
// Set by the playback task when the buffer ran dry mid-stream; polled by the main loop.
bool take_underrun(uint8_t* stream_id);
}  // namespace speaker
