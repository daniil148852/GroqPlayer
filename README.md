# 🤖 GroqPlayer — AI Player Mod for Minecraft 1.20.1

[![Build](https://github.com/yourusername/groqplayer-mod/actions/workflows/build.yml/badge.svg)](https://github.com/yourusername/groqplayer-mod/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green)
![Fabric](https://img.shields.io/badge/Loader-Fabric-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

Adds a fully autonomous **AI player** powered by [Groq API](https://console.groq.com) that behaves like a real player — it mines, fights, eats, explores, and chats.

---

## ✨ Features

- 🧠 **True AI brain** — powered by LLaMA 3 / Mixtral via Groq (free, ultra-fast inference)
- 🎮 **Full player capabilities** — walks, jumps, mines, places blocks, attacks mobs, eats food
- 💬 **Chat awareness** — responds to nearby players, especially when addressed by name
- 🔄 **Survival instincts** — auto-eats when hungry, respawns on death, avoids danger
- 🛠️ **Customizable personality** — set any personality per-bot (e.g. "aggressive griefer", "peaceful builder")
- ⚙️ **Per-server config** — API key + model saved in `config/groqplayer.json`
- 🚀 **Multiple AI players** — spawn as many as you want

---

## 📦 Installation

### Requirements
- Minecraft **1.20.1**
- [Fabric Loader](https://fabricmc.net/use/installer/) ≥ 0.15.0
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 17+

### Steps
1. Download the latest `.jar` from [Releases](https://github.com/yourusername/groqplayer-mod/releases)
2. Place it in your server's `mods/` folder
3. Start the server
4. Get a **free** Groq API key at [console.groq.com](https://console.groq.com)
5. Set the key in-game: `/groqplayer setkey gsk_your_key_here`
6. Spawn your first AI player: `/groqplayer spawn Steve`

---

## 🎮 Commands

All commands require **operator level 2**.

| Command | Description |
|---|---|
| `/groqplayer setkey <key>` | Set Groq API key (saved to config) |
| `/groqplayer setmodel <model>` | Change AI model |
| `/groqplayer spawn <name> [personality]` | Spawn AI player at your position |
| `/groqplayer remove <name>` | Remove an AI player |
| `/groqplayer removeall` | Remove all AI players |
| `/groqplayer list` | List active AI players with stats |
| `/groqplayer help` | Show help |

### Examples

```
/groqplayer spawn Alex "friendly helper who loves building houses"
/groqplayer spawn Goblin "aggressive fighter who attacks players on sight"
/groqplayer spawn Miner "expert miner who always searches for diamonds"
/groqplayer setmodel mixtral-8x7b-32768
```

---

## 🤖 Available Models (Groq)

| Model | Speed | Context | Notes |
|---|---|---|---|
| `llama3-70b-8192` | Fast | 8k | **Default** — Best quality |
| `llama3-8b-8192` | Ultra-fast | 8k | Lighter, still good |
| `mixtral-8x7b-32768` | Fast | 32k | Large context |
| `gemma2-9b-it` | Fast | 8k | Google Gemma |

---

## ⚙️ Configuration

Config file: `config/groqplayer.json`

```json
{
  "apiKey": "gsk_...",
  "model": "llama3-70b-8192",
  "thinkIntervalTicks": 60,
  "maxContextMessages": 20,
  "debugMode": false,
  "chatResponseDistance": 32.0
}
```

| Field | Default | Description |
|---|---|---|
| `apiKey` | `""` | Your Groq API key |
| `model` | `llama3-70b-8192` | Groq model to use |
| `thinkIntervalTicks` | `60` | Ticks between AI decisions (20 ticks = 1 second) |
| `maxContextMessages` | `20` | Chat history to keep in context |
| `debugMode` | `false` | Log all API requests/responses |
| `chatResponseDistance` | `32.0` | Max distance to hear player chat |

---

## 🔧 Building from Source

### Prerequisites
- JDK 17
- Git

```bash
git clone https://github.com/yourusername/groqplayer-mod.git
cd groqplayer-mod
./gradlew build
```

Output: `build/libs/groqplayer-1.0.0+1.20.1.jar`

---

## 🤖 How It Works

1. Every 3 seconds (configurable), the AI player **observes** its surroundings:
   - Position, health, hunger, time, weather
   - Nearby players, hostile mobs, items
   - Blocks around it

2. This state is sent to **Groq API** as a structured prompt

3. The AI responds with a **JSON action**:
   ```json
   {
     "action": "sprint",
     "x": 100, "y": 64, "z": -50,
     "chat": "Found some diamonds over here!"
   }
   ```

4. The mod **executes the action** — movement, mining, attacking, chatting, etc.

5. When a player chats nearby, the AI can **respond in real-time**

---

## 📋 Roadmap

- [ ] Pathfinding (A* navigation)
- [ ] Crafting awareness
- [ ] Inventory management AI
- [ ] Team mode (multiple AI players cooperate)
- [ ] Web GUI for managing bots
- [ ] Voice TTS integration

---

## 📄 License

MIT License — free to use, modify, distribute.
