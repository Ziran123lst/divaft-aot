# DIVA Launcher v1.0-AHBfix

APK with the AHB-swapchain fix bundled. This is the v0.9 (JpegHLE) APK with
**only one change**: `assets/driver/libvulkan_freedreno.so` replaced by a
rebuilt Turnip carrying the u_gralloc `gmsm` fix (see `drivers/turnip-ahbfix/`
and `AHB_FIX_DIAGNOSIS.md`).

- Package: `com.divaft.launcher`, versionCode 4 (overrides v0.7/0.8/0.9 in place).
- Signed with the same debug key as the previous APKs (certificate SHA-256
  `04:90:80:28:...:5E:18`), so it upgrades without uninstalling (no data loss).
- On first launch the launcher extracts the bundled driver to
  `files/vulkan/libvulkan_freedreno.so` and loads it via HMI.

## sha256
See the `.sha256` file next to this APK.

## Verify on device
After installing, `diva-boot.log` should no longer show
`getProperties failed (ErrorInvalidExternalHandle)` / `CreateBuffer: Assertion Failed!`.
If it still fails, send the new log — the error string will tell us the next step.
