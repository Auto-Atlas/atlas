"""OAK-D Pro vision via depthai (Jetson-only). Modeled on
/mnt/ssd/models/nova_vision/nova_cam.py. depthai is imported lazily; absent
hardware/library degrades to a clean 'unavailable' result (never crashes) so
the Jetson body runs the same on a dev box with no camera."""
from __future__ import annotations
from loguru import logger


class OakDCamera:
    def __init__(self):
        self._dai = None
        self._ok = False
        try:
            import depthai as dai  # function-local: Jetson-only
            if dai is None:
                raise ImportError("depthai unavailable")
            self._dai = dai
            self._ok = True
        except Exception as e:  # ImportError on x86, or driver error on Jetson
            logger.warning(f"OAK-D unavailable: {e}")

    def available(self) -> bool:
        return self._ok

    def capture(self) -> dict:
        if not self._ok:
            return {"ok": False, "error": "OAK-D camera unavailable (no depthai/hardware)"}
        try:
            dai = self._dai
            pipeline = dai.Pipeline()
            cam = pipeline.create(dai.node.ColorCamera)
            cam.setPreviewSize(640, 480)
            xout = pipeline.create(dai.node.XLinkOut)
            xout.setStreamName("rgb")
            cam.preview.link(xout.input)
            with dai.Device(pipeline) as dev:
                frame = dev.getOutputQueue("rgb", 1, False).get().getCvFrame()
                h, w = frame.shape[:2]
            return {"ok": True, "width": int(w), "height": int(h),
                    "summary": f"captured {w}x{h} RGB frame from the OAK-D"}
        except Exception as e:
            logger.warning(f"OAK-D capture failed: {e}")
            return {"ok": False, "error": f"OAK-D capture error: {e}"}
