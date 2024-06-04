# Meshtastic Plugin
Offical Meshtastic ATAK Plugin for sending CoT to IMeshService in the Meshtastic Android app.

# Meshtastic Plugin Tool menu
There is now a Meshtastic Tool menu, it currently is only used for recording Voice Memos. Voice memos will record your speech to text then send a Meshtastic text message. If users enable "Text to Speech" in preferecnes the message will be read outloud. Only English is supported currently. The Speech to Text is performed with the Vosk library.

# Settings
The plugin currently has the following settings:
- Enable relay to server, this forwards all CotEvents (except DMs) to any connected TAKServers
- Show all Meshtastic devices, this will place Sensor CoTs on the map for meshtastic devices that have non-zero GPS
- Do not show your local node, this will not place a Sensor CoT on the map for the meshtastic device currently bound to the EUD
- Enable reporting rate controls, this will set ATAK's reporting rate to Constant and allow you to pick a interval from 1,5,10,20,30 minutes
- Reporting rate, the menu to pick the interval in minutes
- Only send PLI and Chat messages, this will only use the atak.protos which are optimized for speed (no libcotshrink)
- Use Text to Speech for incoming messages, this will read outloud any Meshtastic TEXT_MESSAGE_APP (basic meshtastic text messages)
- PTT KeyCode, this allows you to define what hardware key to use to enable voice recording (see the Mestastic Plugin's Tool menu "Voice Memo")
- Meshtastic Channel Index, this allows you to define what channel to send ATAK messages on (0 by default)
- Meshtastic Hop Limit, this allows you to adjust the hop limit for ATAK messages (3 by default, 8 max)
- Allow SWITCH command, this opts-in for allowing nodes to switch your node to Short/Fast for file transfers
