package com.auratv.testfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Shows the launcher entry's label; every alias in the manifest points here. */
public class FixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText(getTitle());
        t.setTextSize(32f);
        setContentView(t);
    }
}
