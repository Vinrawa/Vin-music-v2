import os
import sys

BASE_DIR = r"d:\ASUS\Documents\vin-music-v2"
OUTPUT_DIR = os.path.join(BASE_DIR, "claude_export")
os.makedirs(OUTPUT_DIR, exist_ok=True)

CATEGORIES = {
    "CORE_ENGINE": [
        r"app\src\main\AndroidManifest.xml",
        r"app\build.gradle.kts",
        r"app\src\main\kotlin\com\vinmusic\VinMusicApp.kt",
        r"app\src\main\kotlin\com\vinmusic\MainActivity.kt",
        r"app\src\main\kotlin\com\vinmusic\player\PlayerSingleton.kt",
        r"app\src\main\kotlin\com\vinmusic\player\VinMusicService.kt",
        r"app\src\main\kotlin\com\vinmusic\player\PlayerViewModel.kt",
        r"app\src\main\kotlin\com\vinmusic\player\PlayerCacheManager.kt",
        r"app\src\main\kotlin\com\vinmusic\di\AppModule.kt",
        r"app\src\main\kotlin\com\vinmusic\data\db\VinDatabase.kt",
        r"app\src\main\kotlin\com\vinmusic\data\FirebaseSyncManager.kt"
    ],
    "NETWORK_AND_STREAMING": [
        r"app\src\main\kotlin\com\vinmusic\innertube\InnerTube.kt",
        r"app\src\main\kotlin\com\vinmusic\innertube\YTMusicApi.kt",
        r"app\src\main\kotlin\com\vinmusic\innertube\YTMusicSession.kt",
        r"app\src\main\kotlin\com\vinmusic\innertube\NewPipeDownloader.kt",
        r"app\src\main\kotlin\com\vinmusic\innertube\ExperimentalResolver.kt",
        r"app\src\main\kotlin\com\vinmusic\lyrics\LyricsHelper.kt",
        r"app\src\main\kotlin\com\vinmusic\lyrics\UnisonClient.kt",
        r"app\src\main\kotlin\com\vinmusic\download\DownloadService.kt",
        r"app\src\main\kotlin\com\vinmusic\download\ArtistBannerCache.kt"
    ],
    "UI_AND_SCREENS": [
        r"app\src\main\kotlin\com\vinmusic\ui\screens\HomeScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\FullPlayerScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\SearchScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\LibraryScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\DiscoverScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\PlaylistDetailScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\AlbumDetailScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\ArtistProfileScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\DownloadsScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\SettingsScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\AuthScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\MusicDnaScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\SplashScreen.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\screens\HomeVibeCapsule.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\components\Components.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\components\AmbientFluidGlowBackground.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\theme\DesignSystem.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\theme\VinTheme.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\utils\ColorExtractor.kt",
        r"app\src\main\kotlin\com\vinmusic\ui\utils\ShareCardGenerator.kt"
    ],
    "RECOMMENDATIONS_AND_AUDIO": [
        r"app\src\main\kotlin\com\vinmusic\recommendation\RecommendationManager.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\RecommendationRepository.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\TasteProfileManager.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\FeatureEstimator.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\SpotifyApiService.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\RecommendationDatabase.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\genre\GenreContentFilter.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\genre\GenreModels.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\genre\GenreQueryBuilder.kt",
        r"app\src\main\kotlin\com\vinmusic\recommendation\dsp\BpmEstimator.kt",
        r"app\src\main\kotlin\com\vinmusic\player\AudioFeatureProcessor.kt",
        r"app\src\main\kotlin\com\vinmusic\player\EightDAudioProcessor.kt",
        r"app\src\main\kotlin\com\vinmusic\player\ScratchSoundSynthesizer.kt"
    ]
}

def export_file(rel_path):
    full_path = os.path.join(BASE_DIR, rel_path)
    if not os.path.exists(full_path):
        return f"// File not found: {rel_path}\n"
    ext = os.path.splitext(rel_path)[1]
    lang = "kotlin" if ext == ".kt" else "xml" if ext == ".xml" else "gradle" if ext == ".kts" else "text"
    try:
        with open(full_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
        return f"\n\n{'='*80}\n### FILE: `{rel_path}`\n{'='*80}\n```{lang}\n{content}\n```\n"
    except Exception as e:
        return f"// Error reading {rel_path}: {e}\n"

# 1. Generate category bundle files
for cat_name, file_list in CATEGORIES.items():
    out_file = os.path.join(OUTPUT_DIR, f"{cat_name}.md")
    with open(out_file, "w", encoding="utf-8") as out:
        out.write(f"# Vin Music v2 - Bundle: {cat_name}\n\n")
        out.write(f"This bundle contains key source files for {cat_name}.\n\n")
        for rel_path in file_list:
            print(f"Writing {rel_path} to {cat_name}.md...")
            out.write(export_file(rel_path))

# 2. Generate full bundle containing ALL kotlin and main configuration files
all_files = []
for root, dirs, files in os.walk(os.path.join(BASE_DIR, "app", "src", "main")):
    for f in files:
        if f.endswith(".kt") and not f.endswith(".tmp"):
            all_files.append(os.path.relpath(os.path.join(root, f), BASE_DIR))

# add manifest and gradle
all_files = [r"app\src\main\AndroidManifest.xml", r"app\build.gradle.kts"] + sorted(all_files)

full_bundle = os.path.join(OUTPUT_DIR, "ALL_PROJECT_CODE.md")
with open(full_bundle, "w", encoding="utf-8") as out:
    out.write("# Vin Music v2 - Complete Project Codebase for Claude\n\n")
    out.write("## Overview\n")
    out.write("This file contains the complete source code of Vin Music v2 (Android Media3 + Jetpack Compose).\n\n")
    out.write("## File List\n")
    for f in all_files:
        out.write(f"- `{f}`\n")
    out.write("\n\n")
    for rel_path in all_files:
        print(f"Writing {rel_path} to ALL_PROJECT_CODE.md...")
        out.write(export_file(rel_path))

print("\nSuccessfully generated all Claude export bundles in:", OUTPUT_DIR)
