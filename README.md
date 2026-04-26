# Lavabili

<p align="center">
  <img src="https://img.shields.io/badge/Lavalink-v4-blue" alt="Lavalink Version">
  <img src="https://img.shields.io/badge/Java-17+-orange" alt="Java Version">
  <img src="https://img.shields.io/badge/License-MIT-green" alt="License">
  <img src="https://jitpack.io/v/ParrotXray/lavabili-plugin.svg" alt="JitPack Version">
</p>

A Lavalink plugin that adds **Bilibili** as an audio source, rebuilt for the latest Lavalink v4. This plugin is based on [hoyiliang/lavabili-plugin](https://github.com/hoyiliang/lavabili-plugin) with enhanced features and bug fixes.

## Features

- **Audio Playback**: Stream audio from Bilibili videos
- **Smart Search**: `bilisearch:` prioritizes the Bilibili Audio platform (official releases, singles, albums), falling back to music-category videos only when needed
- **LavaSearch Compatible**: Implements `AudioSearchManager` for use with [LavaSearch](https://github.com/topi314/LavaSearch)
- **Multi-part Videos**: Support for videos with multiple parts (`?p=` parameter)
- **Playlist Support**: Handle Bilibili audio playlists
- **Short URL Support**: Automatically resolve b23.tv short links

## Installation

### Plugin Configuration
Add the plugin to your `application.yml`:

```yaml
lavalink:
  plugins:
    - dependency: "com.github.ParrotXray:lavabili-plugin:VERSION"
      repository: "https://jitpack.io"
      snapshot: false
```

- **Note**: Replace `VERSION` with the latest version shown in the [Releases](https://github.com/ParrotXray/lavabili-plugin/releases) tab or use a commit hash for snapshots.

```yaml
plugins:
  lavabili:
    enabled: true
    allowSearch: true      # Whether "bilisearch:" can be used
    playlistPageCount: -1  # -1 means no limit, or set a specific number
    auth:
      enabled: false # setting "enabled: true" is the bare minimum to get Authentication working.
      # After logging in to Bilibili, open the developer tools and extract the cookie information.
      sessdata: "paste your sessdata here if applicable"
      biliJct: "paste your biliJct here if applicable"
      dedeUserId: "paste your dedeUserId here if applicable"
      buvid3: "paste your buvid3 here if applicable"
      buvid4: "paste your buvid4 here if applicable"
      acTimeValue: "paste your ac_time_value here if applicable" # Used to refresh cookies after the login status expires. Without this value, you can only log in again. If you do not need to refresh the credentials, you do not need to provide it
```

### Optional: LavaSearch Integration

If you use [LavaSearch](https://github.com/topi314/LavaSearch), add it alongside this plugin:

```yaml
lavalink:
  plugins:
    - dependency: "com.github.ParrotXray:lavabili-plugin:VERSION"
      repository: "https://jitpack.io"
    - dependency: "com.github.topi314.lavasearch:lavasearch-plugin:1.0.0"
```

Once both plugins are loaded, `bilisearch:` queries are automatically routed through the `/v4/loadsearch` endpoint.

## Getting Bilibili Cookies for Authentication

For Reference: https://nemo2011.github.io/bilibili-api/#/get-credential

### Quick Method (Python Script)
1. **Clone the repository**
   ```bash
   git clone https://github.com/ParrotXray/lavabili-plugin.git
   cd lavabili-plugin/cookie_extraction
   ```

2. **Install dependencies**
   ```bash
   # Linux/macOS
   pip3 install --no-cache-dir -r requirements.txt --upgrade
  
   # Windows
   pip install --no-cache-dir -r requirements.txt --upgrade
   ```

3. **Execute the script**
   ```bash
   # Linux/macOS
   python3 cookie_extraction.py
  
   # Windows
   python cookie_extraction.py
   ```

4. **Follow the steps to log in and obtain information**

### Manual Method (Browser DevTools)

1. Login to [bilibili.com](https://www.bilibili.com)
2. Press `F12` → `Application` tab → find `Cookie` in the `Storage` tab
3. Find `www.bilibili.com` request
4. Extract values for `SESSDATA`, `bili_jct`, `DedeUserID`, `buvid3`, `buvid4`
5. Find `Local storage` in the `Storage` tab
6. Extract value for `ac_time_value`

**⚠️ Security Warning**: Never share your cookies - they're equivalent to your login credentials!

## Usage Examples

### Direct Video URLs

```
# Single video
https://www.bilibili.com/video/BV1NVWxeeEVJ

# Specific part of multi-part video (plays only part 2)
https://www.bilibili.com/video/BV1NVWxeeEVJ?p=2

# Multi-part video without specific part (loads full playlist)
https://www.bilibili.com/video/BV1NVWxeeEVJ
```

### Search

```
bilisearch:your search query here
```

Search uses a **two-step strategy**:

1. **Bilibili Audio platform** is queried first — these are pure audio uploads (official releases, singles, albums, instrumental tracks).
2. **Music-category videos** (`tids_1=3`, ordered by play count) are used as a **fallback** only if the audio search returns fewer than 5 results. This keeps gaming videos, vlogs and unrelated content out of search results.

Direct URLs are not affected by this logic — any Bilibili URL still loads normally.

### Short URLs

```
# b23.tv short links are automatically resolved
https://b23.tv/abc123
```

### Audio URLs

```
# Single audio track
https://www.bilibili.com/audio/au123456

# Audio playlist
https://www.bilibili.com/audio/am789012
```

## Development

### Prerequisites

- Java 17 or higher
- Git

### Building from Source

1. **Clone the repository**
   ```bash
   git clone https://github.com/ParrotXray/lavabili-plugin.git
   cd lavabili-plugin
   ```

2. **Set execute permissions** (Linux/macOS)
   ```bash
   chmod +x ./gradlew
   ```

3. **Build the project**
   ```bash
   # Linux/macOS
   ./gradlew build
   
   # Windows
   ./gradlew.bat build
   ```

4. **Locate build artifacts**
   - JAR files will be in `build/libs/`
   - File pattern: `lavabili-plugin-<version>.jar`

### Testing Locally

1. **Create configuration file**
   ```bash
   # Create application.yml in project root
   # See Lavalink documentation for configuration examples
   ```

2. **Start test environment**
   ```bash
   # Linux/macOS
   ./gradlew runLavalink
   
   # Windows
   ./gradlew.bat runLavalink
   ```

3. **Test the plugin**
   - Plugin loads automatically
   - Test via Lavalink's REST API or WebSocket
   - Restart with `./gradlew runLavalink` after code changes

### Common Gradle Commands

```bash
# Clean build artifacts
./gradlew clean

# Compile without tests
./gradlew assemble

# Run tests
./gradlew test

# View dependencies
./gradlew dependencies

# Generate test reports
./gradlew test --scan
```

## Supported URL Formats

| Type | Format | Example |
|------|--------|---------|
| **Video** | `bilibili.com/video/BV*` | `https://www.bilibili.com/video/BV1NVWxeeEVJ` |
| **Video (AV)** | `bilibili.com/video/av*` | `https://www.bilibili.com/video/av170001` |
| **Video (Multi-part)** | `bilibili.com/video/BV*?p=N` | `https://www.bilibili.com/video/BV1NVWxeeEVJ?p=2` |
| **Audio Track** | `bilibili.com/audio/au*` | `https://www.bilibili.com/audio/au123456` |
| **Audio Playlist** | `bilibili.com/audio/am*` | `https://www.bilibili.com/audio/am789012` |
| **Short URL** | `b23.tv/*` | `https://b23.tv/abc123` |
| **Search** | `bilisearch:query` | `bilisearch:lofi music` |

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | Boolean | `false` | Enable/disable Bilibili source |
| `allowSearch` | Boolean | `true` | Whether `bilisearch:` can be used |
| `playlistPageCount` | Integer | `-1` | Limit playlist pages (-1 = no limit) |
| `auth.enabled` | Boolean | `false` | Enable cookie authentication |
| `auth.sessdata` | String | `""` | Bilibili session token |
| `auth.biliJct` | String | `""` | CSRF protection token |
| `auth.dedeUserId` | String | `""` | User ID |
| `auth.buvid3` | String | `""` | Device identifier |
| `auth.buvid4` | String | `""` | Device identifier |
| `auth.acTimeValue` | String | `""` | Refresh token for automatic cookie renewal |

## Contributing

Contributions are welcome! Please feel free to submit issues, fork the repository, and create pull requests.

1. Fork the project
2. Create your feature branch (`git switch -c feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Original plugin by [hoyiliang](https://github.com/hoyiliang/lavabili-plugin)
- [Lavalink](https://github.com/lavalink-devs/Lavalink) for the audio framework
- [LavaSearch](https://github.com/topi314/LavaSearch) for the search integration API
- Bilibili for providing the platform

## Support

- [Report Issues](https://github.com/ParrotXray/lavabili-plugin/issues)
- [Discussions](https://github.com/ParrotXray/lavabili-plugin/discussions)
- [Lavalink Documentation](https://lavalink.dev/)
