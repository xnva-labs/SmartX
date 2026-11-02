CXX ?= g++
CXXFLAGS = -std=c++11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -I../../include -I../../src/proto -I.
SRC = ../../src/proto/crc32.cpp ../../src/proto/sha256.cpp ../../src/proto/adpcm.cpp ../../src/proto/frame.cpp ../../src/proto/auth.cpp

test_proto: test_proto.cpp $(SRC) vectors.h ../../include/protocol_ids.h
	$(CXX) $(CXXFLAGS) test_proto.cpp $(SRC) -o test_proto

.PHONY: test clean
test: test_proto
	./test_proto

clean:
	rm -f test_proto
