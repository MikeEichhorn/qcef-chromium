// Copyright 2016 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "core/html/parser/TextResourceDecoder.h"

#include "testing/gtest/include/gtest/gtest.h"

namespace blink {

namespace {

const char kUTF16TextMimeType[] = "text/plain; charset=utf-16";

}  // namespace

TEST(TextResourceDecoderTest, BasicUTF16) {
  std::unique_ptr<TextResourceDecoder> decoder =
      TextResourceDecoder::Create(kUTF16TextMimeType);
  WTF::String decoded;

  const unsigned char kFooLE[] = {0xff, 0xfe, 0x66, 0x00,
                                  0x6f, 0x00, 0x6f, 0x00};
  decoded =
      decoder->Decode(reinterpret_cast<const char*>(kFooLE), sizeof(kFooLE));
  decoded = decoded + decoder->Flush();
  EXPECT_EQ("foo", decoded);

  decoder = TextResourceDecoder::Create(kUTF16TextMimeType);
  const unsigned char kFooBE[] = {0xfe, 0xff, 0x00, 0x66,
                                  0x00, 0x6f, 0x00, 0x6f};
  decoded =
      decoder->Decode(reinterpret_cast<const char*>(kFooBE), sizeof(kFooBE));
  decoded = decoded + decoder->Flush();
  EXPECT_EQ("foo", decoded);
}

TEST(TextResourceDecoderTest, UTF16Pieces) {
  std::unique_ptr<TextResourceDecoder> decoder =
      TextResourceDecoder::Create(kUTF16TextMimeType);

  WTF::String decoded;
  const unsigned char kFoo[] = {0xff, 0xfe, 0x66, 0x00, 0x6f, 0x00, 0x6f, 0x00};
  for (char c : kFoo)
    decoded = decoded + decoder->Decode(reinterpret_cast<const char*>(&c), 1);
  decoded = decoded + decoder->Flush();
  EXPECT_EQ("foo", decoded);
}

}  // namespace blink
