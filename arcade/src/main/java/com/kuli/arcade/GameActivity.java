package com.kuli.arcade;
import android.app.Activity; import android.os.Bundle;
public class GameActivity extends Activity {
    @Override protected void onCreate(Bundle b) { super.onCreate(b); setContentView(new GameView(this, getIntent().getIntExtra("game", 0))); }
}