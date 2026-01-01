# AuraSync

AuraSync is a file synchronization utility designed to keep a folder consistent between a desktop computer and an Android device over a local network.

## Overview

The project consists of two main components:
1.  A desktop engine built with Rust (`async_file_r`).
2.  An Android application (`AuraSync2`).

The desktop application acts as a server that listens for connections, while the Android app acts as a client that discovers and connects to the server. Once connected, both applications monitor a designated `AuraSync` folder and propagate file changes (creations, modifications, and deletions) to each other.

## How It Works

1.  **Discovery**: The Rust desktop application broadcasts a UDP message on the local network to announce its presence. The Android app listens for this broadcast to discover the IP address of the desktop engine.
2.  **Communication**: After discovery, the Android client establishes a persistent TCP connection to the Rust server.
3.  **Synchronization Protocol**: All communication regarding file changes happens over the TCP connection using a custom protocol defined with Protocol Buffers (`sync_event.proto`).
4.  **File Watching**: Both the desktop and Android applications watch for file system events (create, modify, delete) within their respective `AuraSync` folders.
5.  **Event-Driven Sync**: When a change is detected, the application creates a `SyncEvent` message containing:
    *   The type of action (CREATE, MODIFY, DELETE).
    *   The relative path of the file.
    *   The file size.
    *   A **Blake3 checksum** for data integrity.
6.  **Integrity and Echo Prevention**: The checksum is used to verify the integrity of transferred files. It is also used as a mechanism to prevent "echoes," where an application would otherwise re-process a change that it initiated itself.

## Project Structure

-   `async_file_r/`: Contains the Rust-based desktop server application.
-   `AuraSync2/`: Contains the Android client application, built with Jetpack Compose.
-   `src/proto/sync_event.proto`: The shared Protocol Buffers definition file used by both the client and server to ensure they speak the same language.

## Current Status & Limitations

- The application successfully synchronizes files within the `AuraSync` folder.
- **Limitation**: Synchronization is **not recursive**. It only works for files directly inside the `AuraSync` folder and does not handle subdirectories.

## How to Build and Run

### Desktop Engine (Rust)

- Navigate to the `async_file_r` directory.
- Run the application using `cargo run`.
- The server will start and begin broadcasting for discovery.

### Android App

- Open the `AuraSync2` directory in Android Studio.
- Build the project using Gradle.
- Run the app on an Android device connected to the same local network as the desktop engine.
- Tap the "Start" button to begin the discovery and synchronization process.

## Future Improvements

- **Recursive Folder Sync**: Implement support for synchronizing entire directory trees, including subfolders.
- **Conflict Resolution**: Add logic to intelligently handle cases where a file is modified on both the client and server simultaneously.
- **UI/UX Enhancements**: Improve the user interface on both platforms to allow for folder selection, display synchronization status, and manage connections more easily.
- **Robustness**: Enhance error handling for network dropouts and file I/O issues.
- **Cross-platform Desktop Support**: Compile and test the Rust engine for macOS and Linux.