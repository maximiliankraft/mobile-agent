package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

public class MainActivity extends Activity {
    private static final int PAGE_BACKGROUND = Color.rgb(246, 247, 249);
    private static final int INK = Color.rgb(29, 34, 43);
    private static final int MUTED = Color.rgb(100, 108, 120);
    private static final int ACCENT = Color.rgb(54, 91, 214);
    private static final String DEMO_TITLE = "Planning session (demo)";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
    }

    private ScrollView createContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(PAGE_BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(32));
        scrollView.addView(page);

        TextView brand = text("MOBILE AGENT", 13, ACCENT, true);
        brand.setLetterSpacing(0.08f);
        page.addView(brand);

        TextView heading = text("Your assistant.\nYour say.", 32, INK, true);
        heading.setPadding(0, dp(18), 0, dp(10));
        page.addView(heading);

        TextView introduction = text(
                "A local-first Android agent should ask before it acts. This first prototype demonstrates that permission boundary.",
                16, MUTED, false);
        page.addView(introduction);

        LinearLayout proposal = new LinearLayout(this);
        proposal.setOrientation(LinearLayout.VERTICAL);
        proposal.setPadding(dp(20), dp(20), dp(20), dp(20));
        proposal.setBackground(cardBackground(Color.WHITE, dp(18)));
        LinearLayout.LayoutParams proposalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        proposalParams.setMargins(0, dp(28), 0, 0);
        page.addView(proposal, proposalParams);

        TextView label = text("DEMO ACTION PROPOSAL", 12, ACCENT, true);
        label.setLetterSpacing(0.06f);
        proposal.addView(label);

        TextView actionTitle = text("Create a calendar event", 20, INK, true);
        actionTitle.setPadding(0, dp(12), 0, dp(6));
        proposal.addView(actionTitle);

        TextView details = text(
                DEMO_TITLE + "\nTomorrow · 9:00–10:00 AM\n\nNothing is shared until you review and confirm.",
                15, MUTED, false);
        proposal.addView(details);

        Button reviewButton = new Button(this);
        reviewButton.setText("Review action");
        reviewButton.setAllCaps(false);
        reviewButton.setTextColor(Color.WHITE);
        reviewButton.setBackground(cardBackground(ACCENT, dp(12)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        buttonParams.setMargins(0, dp(20), 0, 0);
        proposal.addView(reviewButton, buttonParams);
        reviewButton.setOnClickListener(view -> confirmCalendarAction());

        TextView note = text(
                "This prototype has no AI connection yet. The sample event only opens after you approve it, and your calendar app still asks you to save it.",
                14, MUTED, false);
        note.setPadding(dp(2), dp(18), dp(2), 0);
        page.addView(note);

        return scrollView;
    }

    private void confirmCalendarAction() {
        new AlertDialog.Builder(this)
                .setTitle("Open your calendar?")
                .setMessage(
                        "Mobile Agent will share the demo event title and time with a calendar app. " +
                                "The calendar app will let you review it before saving.")
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Continue", (dialog, which) -> openCalendar())
                .show();
    }

    private void openCalendar() {
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_YEAR, 1);
        start.set(Calendar.HOUR_OF_DAY, 9);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.HOUR_OF_DAY, 1);

        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, DEMO_TITLE)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.getTimeInMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.getTimeInMillis());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "No calendar app is available.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable cardBackground(int color, int cornerRadiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(cornerRadiusDp));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
