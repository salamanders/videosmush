# Gemini Code Understanding

## Project Overview

This project, `videosmush`, is a Kotlin-based command-line application designed for creating timelapse videos with an
adaptive frame blending technique. It takes a source video and "smushes" it down to a shorter timelapse by merging
frames with little difference.

The project leverages several key libraries:

- **Kotlin:** The primary programming language.
- **JavaCV:** For video processing, including reading and writing video files.
- **JOCL (Java OpenCL):** For GPU-accelerated image averaging, which is the core of the frame blending process.
- **KotlinX Coroutines:** For managing asynchronous operations, particularly in the video processing pipeline.

## Building and Running

The project is built using Gradle.

### Building

To build the project, run the following command:

```bash
./gradlew build
```

### Running

The main entry point for the application is the `main` function in
`src/main/kotlin/info/benjaminhill/videosmush/Main.kt`. To run the application, you can use the following Gradle
command:

```bash
./gradlew run
```

**Note:** The `build.gradle.kts` file currently specifies `info.benjaminhill.videosmush.ScriptedLapseKt` as the main
class, which appears to be incorrect. The `run` command above might fail. If it does, you will need to update the
`mainClass` in `build.gradle.kts` to `info.benjaminhill.videosmush.MainKt`.

The application requires a `script.csv` file in the project root directory and video files in the `inputs` directory.

## Development Conventions

- The project uses Kotlin with a functional and asynchronous style, heavily relying on Kotlin Flows.
- The core logic for image averaging is implemented in the `AveragingImage` interface, with a GPU-accelerated
  implementation in `AveragingImageGpu.kt`.
- Video I/O is handled by the utility functions in `VideoFlowIO.kt`.
- The main application logic is in `Main.kt`, which reads the script and orchestrates the video processing pipeline.
