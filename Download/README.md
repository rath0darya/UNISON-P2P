# Android APK download folder

The signed, upgrade-compatible APK will be published here as `UNISON.apk` by the **Build Android APK** workflow after the repository owner configures the four stable signing secrets listed in [ANDROID_APP.md](../ANDROID_APP.md).

The workflow deliberately does not commit a one-off runner-generated debug APK here: its signing certificate would change on later builds and Android could reject the next update as a package/signature conflict.

The APK is not generated until the workflow's Android build passes and stable signing is configured.
