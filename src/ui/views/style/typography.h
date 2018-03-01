// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef UI_VIEWS_STYLE_TYPOGRAPHY_H_
#define UI_VIEWS_STYLE_TYPOGRAPHY_H_

#include "third_party/skia/include/core/SkColor.h"
#include "ui/views/views_export.h"

namespace gfx {
class FontList;
}

namespace views {
namespace style {

// Where a piece of text appears in the UI. This influences size and weight, but
// typically not style or color.
enum TextContext {
  // Embedders can extend this enum with additional values that are understood
  // by the TypographyProvider offered by their ViewsDelegate. Embedders define
  // enum values from VIEWS_TEXT_CONTEXT_END. Values named beginning with
  // "CONTEXT_" represent the actual TextContexts: the rest are markers.
  VIEWS_TEXT_CONTEXT_START = 0,

  // Text that appears on a button control. Usually 12pt. This includes controls
  // with button-like behavior, such as Checkbox.
  CONTEXT_BUTTON = VIEWS_TEXT_CONTEXT_START,

  // Text that appears on an MD-styled dialog button control. Usually 12pt.
  CONTEXT_BUTTON_MD,

  // A title for a dialog window. Usually 15pt. Multi-line OK.
  CONTEXT_DIALOG_TITLE,

  // Text to label a control, usually next to it. "Body 2". Usually 12pt.
  CONTEXT_LABEL,

  // An editable text field. Usually matches CONTROL_LABEL.
  CONTEXT_TEXTFIELD,

  // Text for the menu items that appear in the touch-selection context menu.
  CONTEXT_TOUCH_MENU,

  // Embedders must start TextContext enum values from this value.
  VIEWS_TEXT_CONTEXT_END,

  // All TextContext enum values must be below this value.
  TEXT_CONTEXT_MAX = 0x1000
};

// How a piece of text should be presented. This influences color and style, but
// typically not size.
enum TextStyle {
  // TextStyle enum values must always be greater than any TextContext value.
  // This allows the code to verify at runtime that arguments of the two types
  // have not been swapped.
  VIEWS_TEXT_STYLE_START = TEXT_CONTEXT_MAX,

  // Primary text: solid black, normal weight. Converts to DISABLED in some
  // contexts (e.g. BUTTON_TEXT, FIELD).
  STYLE_PRIMARY = VIEWS_TEXT_STYLE_START,

  // Style for the default button on a dialog.
  STYLE_DIALOG_BUTTON_DEFAULT,

  // Disabled "greyed out" text.
  STYLE_DISABLED,

  // The style used for links. Usually a solid shade of blue.
  STYLE_LINK,

  // Active tab in a tabbed pane.
  STYLE_TAB_ACTIVE,

  // Hovered tab in a tabbed pane.
  STYLE_TAB_HOVERED,

  // Inactive tab in a tabbed pane.
  STYLE_TAB_INACTIVE,

  // Embedders must start TextStyle enum values from here.
  VIEWS_TEXT_STYLE_END
};

// Helpers to obtain font properties from the TypographyProvider given by the
// current ViewsDelegate. |context| can be an enum value from TextContext, or a
// value understood by the embedder's TypographyProvider. Similarly,
// |text_style| corresponds to TextStyle.
VIEWS_EXPORT const gfx::FontList& GetFont(int text_context, int text_style);
VIEWS_EXPORT SkColor GetColor(int text_context, int text_style);
VIEWS_EXPORT int GetLineHeight(int text_context, int text_style);

}  // namespace style
}  // namespace views

#endif  // UI_VIEWS_STYLE_TYPOGRAPHY_H_