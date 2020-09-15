PREREQUISITES:

The OpenCV contribution libraries needs a couple of external libraries in addition to the ones needed by jAER to load in correctly at runtime:

- libtesseract (Ubuntu: tesseract-ocr)
- libdc1394 (Ubuntu: libdc1394-dev)
- intel-mkl (Ubuntu: intel-mkl)

If these aren't satisfied, jAER will complain when you try to load or use the filter.

Cross-platform support is not supported as of now i.e. the libraries either have to target your os + cpu arch, or you would have to compile them yourself.

STEPS TO SET UP ENVIRONMENT FOR ARUCO DEVELOPMENT:

1. extract `opencv_libs.zip` (Linux) or `the_windows_libs.zip` [not checked] (Windows) into jars/
2. invoke `ant` to satisfy dependencies and build the main jAER.jar file
3. (Linux) run the updated script or (Windows) [not implemented] run the appropriate batch file / script
