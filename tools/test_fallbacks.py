import json
import urllib.request

video_id = "Iy-dJwHVX84"

# Let's test Piped instances
piped_instances = [
    f"https://pipedapi.kavin.rocks/streams/{video_id}",
    f"https://api.piped.privacydev.net/streams/{video_id}",
    f"https://pipedapi.tokhmi.xyz/streams/{video_id}",
    f"https://pipedapi.leptons.xyz/streams/{video_id}"
]

for url in piped_instances:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=4) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            audio_streams = data.get("audioStreams", [])
            print(f"[Piped {url[:30]}] Found audio streams: {len(audio_streams)}")
            if audio_streams:
                print("  Audio URL:", audio_streams[0].get("url")[:80])
                break
    except Exception as e:
        print(f"[Piped {url[:30]}] Failed: {e}")

# Let's test Invidious instances
invidious_instances = [
    f"https://invidious.nerdvpn.de/api/v1/videos/{video_id}",
    f"https://inv.tux.pizza/api/v1/videos/{video_id}",
    f"https://vid.puffyan.us/api/v1/videos/{video_id}"
]

for url in invidious_instances:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=4) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            adaptive = data.get("adaptiveFormats", [])
            audio = [f for f in adaptive if "audio" in f.get("type", "")]
            print(f"[Invidious {url[:30]}] Found audio: {len(audio)}")
            if audio:
                print("  Audio URL:", audio[0].get("url")[:80])
                break
    except Exception as e:
        print(f"[Invidious {url[:30]}] Failed: {e}")
