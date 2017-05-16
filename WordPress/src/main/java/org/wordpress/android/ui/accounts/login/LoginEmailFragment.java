package org.wordpress.android.ui.accounts.login;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TextInputLayout;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.BuildConfig;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.generated.AccountActionBuilder;
import org.wordpress.android.fluxc.store.AccountStore.OnAvailabilityChecked;
import org.wordpress.android.ui.accounts.login.nav.LoginNav;
import org.wordpress.android.ui.accounts.login.nav.LoginStateGetter;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.emailchecker2.EmailChecker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

public class LoginEmailFragment extends Fragment implements TextWatcher {
    private static final String KEY_IN_PROGRESS = "KEY_IN_PROGRESS";

    public static final String TAG = "login_email_fragment_tag";
    public static final int MAX_EMAIL_LENGTH = 100;

    private TextInputLayout mEmailEditTextLayout;
    private EditText mEmailEditText;
    private Button mNextButton;
    private ProgressDialog mProgressDialog;

    private LoginNav.InputEmail mLoginNavInputEmail;
    private boolean mEmailAutoCorrected;

    @Inject Dispatcher mDispatcher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.login_email_screen, container, false);

        mEmailEditText = (EditText) rootView.findViewById(R.id.login_email);
        mEmailEditText.addTextChangedListener(this);

        mEmailEditTextLayout = (TextInputLayout) rootView.findViewById(R.id.login_email_layout);

        mNextButton = (Button) rootView.findViewById(R.id.login_email_next_button);
        mNextButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    next(getCleanedEmail());
                }
        });


        autoFillFromBuildConfig();

        mEmailEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null
                        && event.getAction() == KeyEvent.ACTION_UP
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    next(getCleanedEmail());
                }

                // always consume the event so the focus stays in the EditText
                return true;
            }
        });

        rootView.findViewById(R.id.login_email_username_password).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mLoginNavInputEmail != null) {
                    mLoginNavInputEmail.loginViaUsernamePassword();
                }
            }
        });

        return rootView;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Toolbar toolbar = (Toolbar) view.findViewById(R.id.toolbar);
        ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);

        ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState != null) {
            boolean isInProgress = savedInstanceState.getBoolean(KEY_IN_PROGRESS);
            if (isInProgress) {
                showEmailCheckProgressDialog();
            }
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof LoginStateGetter.FsmGetter) {
            mLoginNavInputEmail = ((LoginStateGetter.FsmGetter) context).getLoginStateGetter().getLoginNavInputEmail();
        } else {
            throw new RuntimeException(context.toString() + " must implement LoginStateGetter.FsmGetter");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mLoginNavInputEmail = null;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(KEY_IN_PROGRESS, mProgressDialog != null);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_login, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.help) {
            mLoginNavInputEmail.help();
            return true;
        }

        return false;
    }

    /*
     * auto-fill the username and password from BuildConfig/gradle.properties (developer feature,
     * only enabled for DEBUG releases)
     */
    private void autoFillFromBuildConfig() {
        if (!BuildConfig.DEBUG) return;

        String email = (String) WordPress.getBuildConfigValue(getActivity().getApplication(),
                "DEBUG_DOTCOM_LOGIN_EMAIL");
        if (!TextUtils.isEmpty(email)) {
            mEmailEditText.setText(email);
            AppLog.d(T.NUX, "Auto-filled email from build config");
        }
    }

    private void autoCorrectEmail() {
        if (mEmailAutoCorrected) {
            return;
        }
        final String email = getCleanedEmail();
        // Check if the username looks like an email address
        final Pattern emailRegExPattern = Patterns.EMAIL_ADDRESS;
        Matcher matcher = emailRegExPattern.matcher(email);
        if (!matcher.find()) {
            return;
        }
        // It looks like an email address, then try to correct it
        String suggest = EmailChecker.suggestDomainCorrection(email);
        if (suggest.compareTo(email) != 0) {
            mEmailAutoCorrected = true;
            mEmailEditText.setText(suggest);
            mEmailEditText.setSelection(suggest.length());
        }
    }

    protected void next(String email) {
        if (!NetworkUtils.checkConnection(getActivity())) {
            return;
        }

        autoCorrectEmail();

        if (isValidEmail(email)) {
            showEmailCheckProgressDialog();
            mDispatcher.dispatch(AccountActionBuilder.newIsAvailableEmailAction(email));
        } else {
            showEmailError(R.string.email_invalid);
        }
    }

    private String getCleanedEmail() {
        return EditTextUtils.getText(mEmailEditText).trim();
    }

    private boolean isValidEmail(String email) {
        Pattern emailRegExPattern = Patterns.EMAIL_ADDRESS;
        Matcher matcher = emailRegExPattern.matcher(email);

        return matcher.find() && email.length() <= MAX_EMAIL_LENGTH;
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        updateNextButton();
        mEmailEditTextLayout.setError(null);
    }

    private void updateNextButton() {
        mNextButton.setEnabled(getCleanedEmail().length() > 0);
    }

    private void showEmailError(int messageId) {
        mEmailEditTextLayout.setError(getString(messageId));
    }

    @Override
    public void onStart() {
        super.onStart();
        mDispatcher.register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        mDispatcher.unregister(this);
    }

    private void showEmailCheckProgressDialog() {
        startProgress(getActivity().getString(R.string.checking_email));
    }

    private void startProgress(String message) {
        mNextButton.setEnabled(false);
        mProgressDialog = ProgressDialog.show(getActivity(), "", message, true, true,
                new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                updateNextButton();
            }
        });
    }

    private void endProgress() {
        if (mProgressDialog != null) {
            mProgressDialog.cancel();
        }

        // nullify the reference to denote there is no operation in progress
        mProgressDialog = null;

        updateNextButton();
    }

    // OnChanged events

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAvailabilityChecked(OnAvailabilityChecked event) {
        endProgress();

        if (event.isError()) {
            AppLog.e(T.API, "OnAvailabilityChecked has error: " + event.error.type + " - " + event.error.message);
        }

        switch(event.type) {
            case EMAIL:
                handleEmailAvailabilityEvent(event);
                break;
            default:
                // TODO: we're not expecting any other availability check so, we should never have reached this line
                break;
        }
    }

    /**
     * Handler for an email availability event. If a user enters an email address for their
     * username an API checks to see if it belongs to a wpcom account.  If it exists the magic links
     * flow is followed. Otherwise the self-hosted sign in form is shown.
     * @param event the event emitted
     */
    private void handleEmailAvailabilityEvent(OnAvailabilityChecked event) {
        if (!event.isAvailable) {
            // TODO: Email address exists in WordPress.com so, goto magic link offer screen
            // Email address exists in WordPress.com
            if (mLoginNavInputEmail != null) {
                mLoginNavInputEmail.gotEmail(event.value);
            }
        } else {
            showEmailError(R.string.email_not_registered_wpcom);
        }
    }
}
