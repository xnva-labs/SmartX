#pragma once
#include <stdint.h>

#include "store.h"

namespace diag {
// Serial bring-up console (115200 baud): help | info | batt | led | cam | mic | spk | pair | unpair
void poll(NvsAuthIO& io, const char* hwid);
}
