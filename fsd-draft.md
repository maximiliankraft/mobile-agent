please create a full specification document for the app that is here described. the general idea is:
a hermes agent but as an android app. the most important tool calls would be:

- being able to read/write files and directories. not just in their own workspace but any where on the phone. although if not specified, the default workspace should be use by default. that should be a folder in /storage/emulated/0/mobileagent. so that the user can easiliy find it if opening the file manager. 

- the ability to create any intent and run it. calendar event creation intent has been implemented and tested, that works well already. 

- the ability to read all contacts. so if the user writes: send a message with the event details to Johannes, the agent shall be able to look for all the people named johannes. if necessary ask which one if there are multiple. and then create an intent for either sms, whatsapp or another app. granted if that messaging app supports this of course. 

- the ability to run any arbitrary command on the phone. including su if the device is rooted

- a button not just to select an image to pass it onto the ai model, but also to be able to create a photo

- if the prompt textboard is selected, the keyboard currently overlays it. ensure that the prompt textbox is floating relative to the keyboard

- there are currently two pages: one where the user can set api keys (currently only for deepseek) and a chat window. add a third window where the user can see the files the ai created for itself to note things down. like user.md, soul.md and several skills. just like the hermes agent is able to do. 