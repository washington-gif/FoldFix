# FoldFix

**A workaround for Google Pixel Fold phones that no longer switch to the outer screen when closed.**

On some Pixel Folds, the sensors Android uses to measure the hinge angle stop reporting.
Android then assumes the phone is always open: closing it leaves the inner screen in charge
and the outer screen never takes over. On the affected phone this was built for, recovery mode
still detected "closed" correctly — most likely through the phone's **hall (magnet) sensor**, which still works.

FoldFix watches that hall sensor and tells Android the phone is closed when it is, using the
same `cmd device_state` command available over ADB. No root needed — it uses
[Shizuku](https://shizuku.rikka.app/) for ADB-level permission.

> ⚠️ Unofficial, use at your own risk, no warranty. FoldFix doesn't modify your system;
> everything it does is undone by tapping **Stop & release override** or by rebooting.
> If your phone is under warranty, a repair by Google is the real fix.

---

## Does this apply to my phone?

FoldFix only helps if **the hinge sensor is silent but the hall sensor still works**.
After installing (steps below), check with the app's diagnostic buttons:

1. Tap **Full device_state dump**. Look for
   `mLastHingeAngleSensorEvent = Hinge Angle (wake-up) : null`.
   `null` means Android has received no hinge readings.
2. Tap **Record ALL sensors for 10 s** and fold/unfold the phone fully a couple of times.
   Under *Fold-related sensors*, the **Hall Effect Sensor** should show readings going
   between `0.00` and `1.00`, while **Hinge Angle** shows `SILENT`.

If the hall sensor is also silent, FoldFix can't help — the fault is likely hardware.

Tested on: original Pixel Fold (2023). Other foldables are untested.

## Install

1. **Install Shizuku** from the [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).
2. **Start Shizuku** (once per reboot, unless your Shizuku version starts automatically):
   - Settings → About phone → tap **Build number** 7 times to enable Developer options.
   - Settings → System → Developer options → turn on **Wireless debugging** (needs Wi-Fi).
   - In Shizuku, tap **Pairing**, then in Wireless debugging tap **Pair device with pairing code**
     and type the code into Shizuku's notification. Back in Shizuku, tap **Start**.
3. **Install FoldFix**: download `FoldFix.apk` from the [latest release](../../releases/latest)
   and open it. Allow installing from your browser/Files app when asked.
   Play Protect may warn about an unknown app — tap *More details → Install anyway*.
4. Open FoldFix, allow notifications, tap **Grant Shizuku permission**.
5. The defaults already use the hall sensor (*Any sensor by name* = `hall`, threshold `0.5`,
   closed when ABOVE). Tap **Save & start correction**.
6. Optional: tick **Start after reboot**, and set FoldFix's battery usage to **Unrestricted**
   (App info → App battery usage) so Android doesn't stop it.

Close the phone: the outer screen should take over. Open it: back to the inner screen.

## Tabletop and Tent

The hall sensor only knows *closed* or *not closed*, so half-open postures can't be detected
automatically. Switch them by hand from FoldFix's notification or the **Fold posture**
Quick Settings tile (edit your tiles to add it):

| Posture | What it does |
|---|---|
| **Auto** | Normal: follows the hall sensor. |
| **Tabletop** | Tells Android the phone is half-open, so apps that support it (YouTube, Camera, Meet…) use their tabletop layout. App support varies. |
| **Tent** | Uses rear-display mode: the outer screen is used while the phone stands half-open. Rotation is locked; use **Rotate ↻** in the notification if it's the wrong way up. |

**To get out of any manual posture:** press the power button, or fully close the phone.
FoldFix always returns to Auto after a reboot.

## Troubleshooting

- **Screen ignores touches / stuck on the wrong screen** — press the power button. If that
  doesn't help, restart the phone; FoldFix's override never survives a reboot.
- **Nothing happens when closing** — check Shizuku says *running*, and look at the
  **Service log** at the bottom of FoldFix (it survives restarts). Include it in any bug report.
- **Wrong screen when closed/open** — untick *closed when value is ABOVE threshold* and start again.

## How it works

- `cmd device_state state <id>` asks Android's DeviceStateManager to treat the phone as
  CLOSED (0), HALF_OPENED (1) or REAR_DISPLAY_STATE (3).
- `cmd device_state state reset` hands control back to Android.
- Both run through Shizuku with shell permission. Open-state decisions are left to Android.

## Building it yourself

Push the repo to GitHub — the included workflow builds the APK in Actions (artifact
*FoldFix-apk*). Or open it in Android Studio. Without signing secrets, builds use a debug key.

## License

MIT — see [LICENSE](LICENSE).
