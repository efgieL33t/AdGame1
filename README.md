# Jewel Coloring

宝石のドット絵パズル(Android / Kotlin、外部ライブラリなし)。

## 遊び方
- 絵は同じ色の宝石がつながった「ピース」でできています。ピースをタップすると宝石が下のトレイへ移動します。
- トレイで同じ色が10個そろうと消えます。盤面からその色が無くなった場合は、トレイに残った同色もまとめて消えます。
- 盤面の宝石をすべて回収するとクリア(完成した絵が表示されます)。
- どのピースもトレイに入りきらなくなると失敗です。レベルが上がると色数・盤面サイズが増え、トレイの使える枠が減ります。

## ビルド
- Android Studio で開いて実行、または `./gradlew assembleDebug`
- push すると GitHub Actions がテストとデバッグAPKのビルドを行い、Artifacts に `jewel-coloring-debug-apk` を保存します。

## 構成
- `Game.kt` … ルール(ピース分割・トレイ・消去判定)
- `LevelGenerator.kt` … レベルごとの対称模様の生成
- `GameView.kt` … 描画・アニメーション・タッチ操作(Canvas)
- `MainActivity.kt`
