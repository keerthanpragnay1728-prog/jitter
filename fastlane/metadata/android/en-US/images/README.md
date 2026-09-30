# Store images

The screenshots are not generated. Each is a real capture from a release build,
taken by hand. The icon is the one exception: `tools/gen-icon.py` writes it
from the same shapes as the launcher icon's vector layers, so rerun the script
rather than editing the PNG. File names and sizes follow the fastlane layout
that F-Droid reads.

Before capturing: a test profile with no personal notifications, messages or
account names on screen, and the default font size.

## Required

The repository README shows 1, 2 and 4, in that order.

- `icon.png`: 512 x 512, the launcher icon masked to a circle on a transparent
  background. Generated; see above.
- `phoneScreenshots/1.png`: the console, fresh, with the quick-launch rows and
  Bit visible.
- `phoneScreenshots/2.png`: the ledger page, with today's screen time and the
  CYCLE line showing.
- `phoneScreenshots/3.png`: the launch gate during its countdown, on a tracked
  app.
- `phoneScreenshots/4.png`: the launch gate at zero, showing the lease choices.
- `phoneScreenshots/5.png`: the first-run guide on step one, after a return from
  Settings, with the restricted-setting unlock steps showing.
- `phoneScreenshots/6.png`: CFG with Setup ticked and Target apps open.

## Optional

- `phoneScreenshots/7.png`: the lock confirmation panel for a long lock.
- `phoneScreenshots/8.png`: the LEASE EXPIRED gate over the home screen.
- `featureGraphic.png`: 1024 x 500. Leave out rather than make one up.

A stall is not worth a screenshot. It is a frozen frame of another app with a
thin bar along the top, and the other app's content is not ours to publish.
