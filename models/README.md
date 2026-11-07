# Bundled background-removal model

`u2netp.onnx` is the small U²-Net model distributed by [rembg](https://github.com/danielgatis/rembg). It is used unchanged with a bounded 320×320 input and CPU execution. No model download is needed during ordinary use.

The Android app bundles this same file as an asset and runs it on the phone for free, offline **Detect clothing**; the PC uses it for cutouts, detection and automatic framing. The build reads this folder directly, so the model is stored once.

- Model origin: [U-2-Net by Xuebin Qin and colleagues](https://github.com/xuebinqin/U-2-Net).
- Download: https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx
- Verified upstream MD5: `8e83ca70e441ab06c318d82300c84806`.
- SHA-256: `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`.
- U-2-Net's Apache 2.0 license is included as `U2NET-LICENSE.txt`; rembg's MIT notice is included as `REMBG-LICENSE.txt`.

Cutouts can need manual correction. A successful model load or synthetic test does not establish quality for every photograph.
