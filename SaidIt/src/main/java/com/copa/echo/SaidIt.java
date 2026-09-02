package com.copa.echo;

public class SaidIt {

    static final String PACKAGE_NAME = "com.copa.echo";
    static final String AUDIO_MEMORY_ENABLED_KEY = "audio_memory_enabled";
    static final String AUDIO_MEMORY_SIZE_KEY = "audio_memory_size";
    static final String SAMPLE_RATE_KEY = "sample_rate";
    static final String AUTO_SAVE_ENABLED_KEY = "auto_save_enabled";
    static final String AUTO_SAVE_INTERVAL_KEY = "auto_save_interval_minutes";
    static final int AUTO_SAVE_INTERVAL_DEFAULT = 5;
    static final String LOW_POWER_KEY = "low_power";
    static final String GPS_ENABLED_KEY = "gps_enabled";
    static final String PRE_LOW_POWER_SAMPLE_RATE_KEY = "pre_low_power_sample_rate";
    /** Take a photo from both cameras whenever the phone is tilted past the threshold below. */
    static final String CAMERA_ENABLED_KEY = "camera_capture_enabled";
    static final String TILT_THRESHOLD_KEY = "tilt_threshold_degrees";
    /** Degrees of tilt away from lying flat that triggers a capture. */
    static final int TILT_THRESHOLD_DEFAULT = 45;
    /** Shortest seconds between two captures, kept apart for each of the three kinds. */
    static final String CAMERA_MIN_BACK_KEY = "camera_min_back_seconds";
    static final String CAMERA_MIN_FRONT_KEY = "camera_min_front_seconds";
    static final String SCREENSHOT_MIN_KEY = "screenshot_min_seconds";
    static final int CAMERA_MIN_BACK_DEFAULT = 8;
    static final int CAMERA_MIN_FRONT_DEFAULT = 8;
    static final int SCREENSHOT_MIN_DEFAULT = 60;
    /** Send saved traces to a server and delete them once accepted. */
    static final String UPLOAD_ENABLED_KEY = "upload_enabled";
    static final String UPLOAD_URL_KEY = "upload_url";
    /** Sample rate low power mode drops to: enough for speech, a sixth of the data of 48 kHz. */
    static final int LOW_POWER_SAMPLE_RATE = 8000;
    /** How often a location fix is asked for while logging. */
    static final long GPS_INTERVAL_MILLIS = 5000;
    /** Low power mode wants the GPS radio awake as rarely as the microphone. */
    static final long LOW_POWER_GPS_INTERVAL_MILLIS = 30000;
    static final String SKU = "unlimited_history";
    static final String BASE64_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlD0FMFGp4AWzjW" +
            "LTsUZgm0soga0mVVNGFj0qoATaoQCE/LamF7yrMCIFm9sEOB1guCEhzdr16sjysrVc2EPRisS83FoJ4K0R8" +
            "XPDP2TrVT2SAeQpTCG27NNH+W86SlGEqQeQhMPMhR+HDTckHv3KBpD8BZEEIbkXPv6SGFqcZub6xzn9r14l" +
            "6ptYIWboKGGBh1i9/nJpdhCMPxuLn/WZnRXGxqGpfNw2xT25/muUDZgRVezy6/5eI+ciMn5H1U0ADBjXvl1" +
            "Py+4ClkR1V1Mfo9lvauB03zM8Fsa3LlIPle5a+wGKsRCLW/rJ/eE/rje6X7x/n+w8J4OiFvVATj0T8QIDAQ" +
            "AB";

}
