config.set({
    "browsers": ["MyChromeHeadless"],
    "customLaunchers": {
        "MyChromeHeadless": {
            base: "ChromeHeadless",
            flags: [
                "--allow-failed-policy-fetch-for-test",
                "--allow-external-pages",
                "--no-sandbox",
                "--disable-web-security",
                "--disable-setuid-sandbox",
                "--enable-logging",
                "--v=1"
            ]
        }
    },
    "client": {
        captureConsole: true,
        "mocha": {
            // Disable timeout as we use individual timeouts for tests
            timeout: 0
        }
    }
});