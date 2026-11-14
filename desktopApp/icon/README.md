# Application icon

`rabbit.png` and `rabbit.icns` in `desktopApp/src/main/resources/` are original artwork made for this project (a rabbit peeking over a stream log). They are not the RabbitMQ logo and do not use any third-party artwork. They are covered by the project's MIT license.

`Icon.java` draws the 1024 px master image. Regenerate the files on macOS:

```
java Icon.java rabbit-1024.png
mkdir rabbit.iconset
for sz in 16 32 128 256 512; do
  sips -z $sz $sz rabbit-1024.png --out rabbit.iconset/icon_${sz}x${sz}.png
  sips -z $((sz*2)) $((sz*2)) rabbit-1024.png --out rabbit.iconset/icon_${sz}x${sz}@2x.png
done
iconutil -c icns rabbit.iconset -o rabbit.icns
sips -z 512 512 rabbit-1024.png --out rabbit.png
```
