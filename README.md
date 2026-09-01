Echo
====

Time travelling recorder for Android.
It is free/libre and gratis software.

Architecture
---

**SaidItFragment** the main view of the app.

**SaidItService** manages a high priority thread that records audio. The thread is a state machine that can be accessed by sending it tasks using Android's Handler (`audioHandler`).

**AudioMemory** (not thread-safe) manages the in-memory ring buffer of audio chunks.

**GpxTrack** (thread-safe) buffers location fixes and writes them out as a GPX track.

Traces
---

Echo writes two kinds of trace into a shared `Echo` directory at the root of external storage,
falling back to app storage when that cannot be written to. Every save writes
`yyyyMMdd_HHmmss.wav`, named after the wall clock time of its first sample, with a `_2`, `_3`...
suffix when that second already has a file. With GPS logging on, the fixes taken while that
audio was captured are written as `yyyyMMdd_HHmmss.gpx` under exactly the same name, so a
recording and its track are easy to pair up.

They are independent files throughout: each is listed, opened and deleted on its own, and
deleting one never touches the other. **Traces** lists both kinds, and still reads the old
`Music/Echo` directory so traces recorded before 2.2 stay visible.

Sharing the microphone
---

Android hands the microphone input to one app at a time. When a call or a higher priority app
takes it, Echo's capture is not stopped: it is fed silence. So Echo asks for a capture that is
not privacy sensitive, which is what lets another app record alongside it where the platform
allows that at all, and watches `AudioManager.AudioRecordingCallback` to know when it is being
silenced rather than writing digital zeros and calling them a recording. The moment the input is
free again the `AudioRecord` is opened afresh, and a watchdog reopens it anyway if audio simply
stops arriving, so a microphone borrowed by another app never leaves Echo permanently deaf.
