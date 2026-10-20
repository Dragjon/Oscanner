# OpenCV's native code looks up its Java classes and fields by name.
-keep class org.opencv.** { *; }

# ONNX Runtime's native code also creates and reads its Java objects by name.
-keep class ai.onnxruntime.** { *; }
