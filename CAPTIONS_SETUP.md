# Live Captions Setup (Google Cloud Speech-to-Text)

To enable live captions during WhatsApp calls:

## 1. Create a Google Cloud project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one

## 2. Enable Speech-to-Text API

1. Open [Speech-to-Text API](https://console.cloud.google.com/apis/library/speech.googleapis.com)
2. Click **Enable**

## 3. Create an API key

1. Go to [Credentials](https://console.cloud.google.com/apis/credentials)
2. Click **Create Credentials** → **API key**
3. Copy the API key
4. (Recommended) Restrict the key to Speech-to-Text API only

## 4. Add the API key to your project

Add this line to `gradle.properties` (create the file in project root if it doesn't exist):

```
GOOGLE_CLOUD_SPEECH_API_KEY=your_api_key_here
```

**Important:** Do not commit `gradle.properties` with your API key to version control. Add it to `.gitignore` or use a local override.

## 5. Overlay permission

The app needs **"Display over other apps"** permission to show captions. If captions don't appear, go to **Settings → Apps → [Your App] → Display over other apps** and enable it. Without this, the overlay cannot be shown.

**Debugging:** Filter logcat by `WARecorder` to see caption logs. You should see:
- `[CaptionPipeline] First PCM chunk received` — audio is reaching the pipeline
- `[CaptionPipeline] Sending batch to STT` — batches are being sent to Google
- `[SpeechToText] Transcript:` — API returned text
- `[RecService] CANNOT show caption overlay` — overlay permission missing

## 6. Billing (free tier)

Google Cloud Speech-to-Text offers **60 minutes per month free**. For a hackathon, this is usually sufficient. You may need to enable billing on the project, but you won't be charged within the free tier.
