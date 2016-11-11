package org.wordpress.android.ui.accounts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentTransaction;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.gms.auth.api.credentials.Credential;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.BuildConfig;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.analytics.AnalyticsTracker.Stat;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.action.AccountAction;
import org.wordpress.android.fluxc.generated.AccountActionBuilder;
import org.wordpress.android.fluxc.generated.AuthenticationActionBuilder;
import org.wordpress.android.fluxc.generated.SiteActionBuilder;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.network.HTTPAuthManager;
import org.wordpress.android.fluxc.network.MemorizingTrustManager;
import org.wordpress.android.fluxc.network.discovery.SelfHostedEndpointFinder.DiscoveryError;
import org.wordpress.android.fluxc.store.AccountStore;
import org.wordpress.android.fluxc.store.AccountStore.AuthenticatePayload;
import org.wordpress.android.fluxc.store.AccountStore.AuthenticationErrorType;
import org.wordpress.android.fluxc.store.AccountStore.OnAccountChanged;
import org.wordpress.android.fluxc.store.AccountStore.OnAuthenticationChanged;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.fluxc.store.SiteStore.OnSiteChanged;
import org.wordpress.android.fluxc.store.SiteStore.RefreshSitesXMLRPCPayload;
import org.wordpress.android.networking.OAuthAuthenticator;
import org.wordpress.android.ui.notifications.utils.SimperiumUtils;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.util.AnalyticsUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.HelpshiftHelper;
import org.wordpress.android.util.HelpshiftHelper.Tag;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.SelfSignedSSLUtils;
import org.wordpress.android.util.SelfSignedSSLUtils.Callback;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.util.WPUrlUtils;
import org.wordpress.android.widgets.WPTextView;
import org.wordpress.emailchecker2.EmailChecker;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

public class SignInFragment extends AbstractFragment implements TextWatcher {
    public static final String TAG = "sign_in_fragment_tag";
    private static final String DOT_COM_BASE_URL = "https://wordpress.com";
    private static final String FORGOT_PASSWORD_RELATIVE_URL = "/wp-login.php?action=lostpassword";
    private static final String XMLRPC_SUPPORT_URL = "https://codex.wordpress.org/XML-RPC_Support";
    private static final int WPCOM_ERRONEOUS_LOGIN_THRESHOLD = 3;
    private static final String KEY_IS_SELF_HOSTED = "IS_SELF_HOSTED";

    public static final String ENTERED_URL_KEY = "ENTERED_URL_KEY";
    public static final String ENTERED_USERNAME_KEY = "ENTERED_USERNAME_KEY";

    protected EditText mUsernameEditText;
    protected EditText mPasswordEditText;
    protected EditText mUrlEditText;
    protected EditText mTwoStepEditText;

    protected LinearLayout mBottomButtonsLayout;
    protected RelativeLayout mUsernameLayout;
    protected RelativeLayout mPasswordLayout;
    protected RelativeLayout mProgressBarSignIn;
    protected RelativeLayout mUrlButtonLayout;
    protected RelativeLayout mTwoStepLayout;
    protected LinearLayout mTwoStepFooter;

    protected boolean mSelfHosted;
    protected boolean mEmailAutoCorrected;
    protected boolean mShouldSendTwoStepSMS;
    protected int mErroneousLogInCount;
    protected String mUsername;
    protected String mPassword;
    protected String mTwoStepCode;
    protected String mHttpUsername;
    protected String mHttpPassword;
    protected SiteModel mJetpackSite;

    protected WPTextView mSignInButton;
    protected WPTextView mCreateAccountButton;
    protected WPTextView mAddSelfHostedButton;
    protected WPTextView mProgressTextSignIn;
    protected WPTextView mForgotPassword;
    protected WPTextView mJetpackAuthLabel;
    protected ImageView mInfoButton;
    protected ImageView mInfoButtonSecondary;

    private RefreshSitesXMLRPCPayload mSelfhostedPayload;

    protected @Inject SiteStore mSiteStore;
    protected @Inject AccountStore mAccountStore;
    protected @Inject Dispatcher mDispatcher;
    protected @Inject HTTPAuthManager mHTTPAuthManager;
    protected @Inject MemorizingTrustManager mMemorizingTrustManager;

    protected boolean mSitesFetched = false;
    protected boolean mAccountSettingsFetched = false;
    protected boolean mAccountFetched = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        if (savedInstanceState != null) {
            mSelfHosted = savedInstanceState.getBoolean(KEY_IS_SELF_HOSTED);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.signin_fragment, container, false);
        mUrlButtonLayout = (RelativeLayout) rootView.findViewById(R.id.url_button_layout);
        mTwoStepLayout = (RelativeLayout) rootView.findViewById(R.id.two_factor_layout);
        mTwoStepFooter = (LinearLayout) rootView.findViewById(R.id.two_step_footer);
        mUsernameLayout = (RelativeLayout) rootView.findViewById(R.id.nux_username_layout);
        mUsernameLayout.setOnClickListener(mOnLoginFormClickListener);
        mPasswordLayout = (RelativeLayout) rootView.findViewById(R.id.nux_password_layout);
        mPasswordLayout.setOnClickListener(mOnLoginFormClickListener);

        mUsernameEditText = (EditText) rootView.findViewById(R.id.nux_username);
        mUsernameEditText.addTextChangedListener(this);
        mUsernameEditText.setOnClickListener(mOnLoginFormClickListener);
        mPasswordEditText = (EditText) rootView.findViewById(R.id.nux_password);
        mPasswordEditText.addTextChangedListener(this);
        mPasswordEditText.setOnClickListener(mOnLoginFormClickListener);
        mJetpackAuthLabel = (WPTextView) rootView.findViewById(R.id.nux_jetpack_auth_label);
        mUrlEditText = (EditText) rootView.findViewById(R.id.nux_url);
        mSignInButton = (WPTextView) rootView.findViewById(R.id.nux_sign_in_button);
        mSignInButton.setOnClickListener(mSignInClickListener);
        mProgressBarSignIn = (RelativeLayout) rootView.findViewById(R.id.nux_sign_in_progress_bar);
        mProgressTextSignIn = (WPTextView) rootView.findViewById(R.id.nux_sign_in_progress_text);
        mCreateAccountButton = (WPTextView) rootView.findViewById(R.id.nux_create_account_button);
        mCreateAccountButton.setOnClickListener(mCreateAccountListener);
        mAddSelfHostedButton = (WPTextView) rootView.findViewById(R.id.nux_add_selfhosted_button);
        mAddSelfHostedButton.setText(getString(R.string.nux_add_selfhosted_blog));
        mAddSelfHostedButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSignInMode();
            }
        });

        mForgotPassword = (WPTextView) rootView.findViewById(R.id.forgot_password);
        mForgotPassword.setOnClickListener(mForgotPasswordListener);
        mUsernameEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    autocorrectUsername();
                }
            }
        });

        mPasswordEditText.setOnEditorActionListener(mEditorAction);
        mUrlEditText.setOnEditorActionListener(mEditorAction);

        mTwoStepEditText = (EditText) rootView.findViewById(R.id.nux_two_step);
        mTwoStepEditText.addTextChangedListener(this);
        mTwoStepEditText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if ((event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) || (keyCode == EditorInfo.IME_ACTION_DONE)) {
                    if (fieldsFilled()) {
                        signIn();
                    }
                }

                return false;
            }
        });

        WPTextView twoStepFooterButton = (WPTextView) rootView.findViewById(R.id.two_step_footer_button);
        twoStepFooterButton.setText(Html.fromHtml("<u>" + getString(R.string.two_step_footer_button) + "</u>"));
        twoStepFooterButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                requestSMSTwoStepCode();
            }
        });

        mBottomButtonsLayout = (LinearLayout) rootView.findViewById(R.id.nux_bottom_buttons);
        initPasswordVisibilityButton(rootView, mPasswordEditText);
        initInfoButtons(rootView);
        moveBottomButtons();

        if (mSelfHosted) {
            showSelfHostedSignInForm();
        }
        autofillFromBuildConfig();
        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Ensure two-step form is shown if needed
        if (!TextUtils.isEmpty(mTwoStepEditText.getText()) && mTwoStepLayout.getVisibility() == View.GONE) {
            setTwoStepAuthVisibility(true);
        }

        // show progress indicator while waiting for network response when migrating access token
        if (AppPrefs.wasAccessTokenMigrated() && checkNetworkConnectivity()) {
            startProgress(getString(R.string.access_token_migration_message));
        }
    }

    /**
     * Hide toggle button "add self hosted / sign in with WordPress.com" and show self hosted URL
     * edit box
     */
    public void forceSelfHostedMode(@NonNull String prefillUrl) {
        mUrlButtonLayout.setVisibility(View.VISIBLE);
        mAddSelfHostedButton.setVisibility(View.GONE);
        mCreateAccountButton.setVisibility(View.GONE);
        if (!prefillUrl.isEmpty()) {
            mUrlEditText.setText(prefillUrl);
        }
        mSelfHosted = true;
    }

    protected void toggleSignInMode(){
        if (mUrlButtonLayout.getVisibility() == View.VISIBLE) {
            showDotComSignInForm();
            mSelfHosted = false;
        } else {
            showSelfHostedSignInForm();
            mSelfHosted = true;
        }
    }

    protected void showDotComSignInForm(){
        mUrlButtonLayout.setVisibility(View.GONE);
        mAddSelfHostedButton.setText(getString(R.string.nux_add_selfhosted_blog));
    }

    protected void showSelfHostedSignInForm(){
        mUrlButtonLayout.setVisibility(View.VISIBLE);
        mAddSelfHostedButton.setText(getString(R.string.nux_oops_not_selfhosted_blog));
    }

    protected void track(Stat stat, Map<String, Boolean> properties) {
        AnalyticsTracker.track(stat, properties);
    }

    protected void finishCurrentActivity(final List<Map<String, Object>> userBlogList) {
        mUrlEditText.setText("");

        if (!isAdded()) {
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (userBlogList != null) {
                    getActivity().setResult(Activity.RESULT_OK);
                    getActivity().finish();
                }
            }
        });
    }

    private void initInfoButtons(View rootView) {
        OnClickListener infoButtonListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), HelpActivity.class);
                // Used to pass data to an eventual support service
                intent.putExtra(ENTERED_URL_KEY, EditTextUtils.getText(mUrlEditText));
                intent.putExtra(ENTERED_USERNAME_KEY, EditTextUtils.getText(mUsernameEditText));
                intent.putExtra(HelpshiftHelper.ORIGIN_KEY, Tag.ORIGIN_LOGIN_SCREEN_HELP);
                startActivity(intent);
            }
        };
        mInfoButton = (ImageView) rootView.findViewById(R.id.info_button);
        mInfoButtonSecondary = (ImageView) rootView.findViewById(R.id.info_button_secondary);
        mInfoButton.setOnClickListener(infoButtonListener);
        mInfoButtonSecondary.setOnClickListener(infoButtonListener);
    }

    /*
     * autofill the username and password from BuildConfig/gradle.properties (developer feature,
     * only enabled for DEBUG releases)
     */
    private void autofillFromBuildConfig() {
        if (!BuildConfig.DEBUG) return;

        String userName = (String) WordPress.getBuildConfigValue(getActivity().getApplication(),
                "DEBUG_DOTCOM_LOGIN_USERNAME");
        String password = (String) WordPress.getBuildConfigValue(getActivity().getApplication(),
                "DEBUG_DOTCOM_LOGIN_PASSWORD");
        if (!TextUtils.isEmpty(userName)) {
            mUsernameEditText.setText(userName);
            AppLog.d(T.NUX, "Autofilled username from build config");
        }
        if (!TextUtils.isEmpty(password)) {
            mPasswordEditText.setText(password);
            AppLog.d(T.NUX, "Autofilled password from build config");
        }
    }

    public boolean canAutofillUsernameAndPassword() {
        return EditTextUtils.getText(mUsernameEditText).isEmpty()
                && EditTextUtils.getText(mPasswordEditText).isEmpty()
                && mUsernameEditText != null
                && mPasswordEditText != null;
    }

    public void onCredentialRetrieved(Credential credential) {
        AppLog.d(T.NUX, "Retrieved username from SmartLock: " + credential.getId());
        if (isAdded() && canAutofillUsernameAndPassword()) {
            track(Stat.LOGIN_AUTOFILL_CREDENTIALS_FILLED, null);
            mUsernameEditText.setText(credential.getId());
            mPasswordEditText.setText(credential.getPassword());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        moveBottomButtons();
    }

    private void setSecondaryButtonVisible(boolean visible) {
        mInfoButtonSecondary.setVisibility(visible ? View.VISIBLE : View.GONE);
        mInfoButton.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void moveBottomButtons() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mBottomButtonsLayout.setOrientation(LinearLayout.HORIZONTAL);
            if (getResources().getInteger(R.integer.isSW600DP) == 0) {
                setSecondaryButtonVisible(true);
            } else {
                setSecondaryButtonVisible(false);
            }
        } else {
            mBottomButtonsLayout.setOrientation(LinearLayout.VERTICAL);
            setSecondaryButtonVisible(false);
        }
    }

    private final OnClickListener mOnLoginFormClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            // Don't change layout if we are performing a network operation
            if (mProgressBarSignIn.getVisibility() == View.VISIBLE) return;

            if (mTwoStepLayout.getVisibility() == View.VISIBLE) {
                setTwoStepAuthVisibility(false);
            }
        }
    };

    private void autocorrectUsername() {
        if (mEmailAutoCorrected) {
            return;
        }
        final String email = EditTextUtils.getText(mUsernameEditText).trim();
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
            mUsernameEditText.setText(suggest);
            mUsernameEditText.setSelection(suggest.length());
        }
    }

    private boolean isWPComLogin() {
        String selfHostedUrl = EditTextUtils.getText(mUrlEditText).trim();
        return !mSelfHosted || TextUtils.isEmpty(selfHostedUrl) ||
                WPUrlUtils.isWordPressCom(UrlUtils.addUrlSchemeIfNeeded(selfHostedUrl, false));
    }

    private boolean isJetpackAuth() {
        return mJetpackSite != null;
    }

    // Set blog for Jetpack auth
    public void setBlogAndCustomMessageForJetpackAuth(SiteModel site, String customAuthMessage) {
        mJetpackSite = site;
        if(customAuthMessage != null && mJetpackAuthLabel != null) {
            mJetpackAuthLabel.setText(customAuthMessage);
        }

        if (mAddSelfHostedButton != null) {
            mJetpackAuthLabel.setVisibility(View.VISIBLE);
            mAddSelfHostedButton.setVisibility(View.GONE);
            mCreateAccountButton.setVisibility(View.GONE);
            mUsernameEditText.setText("");
        }
    }

    private final View.OnClickListener mCreateAccountListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            createUserFragment();
        }
    };

    private void createUserFragment() {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        NewUserFragment newUserFragment = NewUserFragment.newInstance();
        newUserFragment.setTargetFragment(this, NewUserFragment.NEW_USER);
        transaction.setCustomAnimations(R.anim.activity_slide_in_from_right, R.anim.activity_slide_out_to_left,
                R.anim.activity_slide_in_from_left, R.anim.activity_slide_out_to_right);
        transaction.replace(R.id.fragment_container, newUserFragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    private String getForgotPasswordURL() {
        String baseUrl = DOT_COM_BASE_URL;
        if (!isWPComLogin()) {
            baseUrl = EditTextUtils.getText(mUrlEditText).trim();
            String lowerCaseBaseUrl = baseUrl.toLowerCase(Locale.getDefault());
            if (!lowerCaseBaseUrl.startsWith("https://") && !lowerCaseBaseUrl.startsWith("http://")) {
                baseUrl = "http://" + baseUrl;
            }
        }
        return baseUrl + FORGOT_PASSWORD_RELATIVE_URL;
    }

    private final View.OnClickListener mForgotPasswordListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            String forgotPasswordUrl = getForgotPasswordURL();
            AppLog.i(T.NUX, "User tapped forgot password link: " + forgotPasswordUrl);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(forgotPasswordUrl));
            startActivity(intent);
        }
    };

    protected void onDoneAction() {
        signIn();
    }

    private final TextView.OnEditorActionListener mEditorAction = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            if (mPasswordEditText == v) {
                if (mSelfHosted) {
                    mUrlEditText.requestFocus();
                    return true;
                } else {
                    return onDoneEvent(actionId, event);
                }
            }
            return onDoneEvent(actionId, event);
        }
    };

    private void trackAnalyticsSignIn() {
        AnalyticsUtils.refreshMetadata(mAccountStore, mSiteStore);
        Map<String, Boolean> properties = new HashMap<String, Boolean>();
        properties.put("dotcom_user", isWPComLogin());
        track(Stat.SIGNED_IN, properties);
        if (!isWPComLogin()) {
            track(Stat.ADDED_SELF_HOSTED_SITE, null);
        }
    }

    private void finishCurrentActivity() {
        if (!isAdded()) {
            return;
        }
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getActivity().setResult(Activity.RESULT_OK);
                getActivity().finish();
            }
        });
    }

    private SmartLockHelper getSmartLockHelper() {
        if (getActivity() != null && getActivity() instanceof SignInActivity) {
            return ((SignInActivity) getActivity()).getSmartLockHelper();
        }
        return null;
    }

    public void showAuthErrorMessage() {
        if (mJetpackAuthLabel != null) {
            mJetpackAuthLabel.setVisibility(View.VISIBLE);
            mJetpackAuthLabel.setText(getResources().getString(R.string.auth_required));
        }
    }

    private void setTwoStepAuthVisibility(boolean isVisible) {
        mTwoStepLayout.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        mTwoStepFooter.setVisibility(isVisible ? View.VISIBLE : View.GONE);
        mSignInButton.setText(isVisible ? getString(R.string.verify) : getString(R.string.sign_in));
        mForgotPassword.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        mBottomButtonsLayout.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        mUsernameEditText.setFocusableInTouchMode(!isVisible);
        mUsernameLayout.setAlpha(isVisible ? 0.6f : 1.0f);
        mPasswordEditText.setFocusableInTouchMode(!isVisible);
        mPasswordLayout.setAlpha(isVisible ? 0.6f : 1.0f);

        if (isVisible) {
            mTwoStepEditText.requestFocus();
            mTwoStepEditText.setText("");
            showSoftKeyboard();
        } else {
            mTwoStepEditText.setText("");
            mTwoStepEditText.clearFocus();
        }
    }

    private void showSoftKeyboard() {
        if (isAdded() && !hasHardwareKeyboard()) {
            InputMethodManager inputMethodManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    private boolean hasHardwareKeyboard() {
        return (getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS);
    }

    private void signInAndFetchBlogListWPCom() {
        startProgress(getString(R.string.connecting_wpcom));
        AuthenticatePayload payload = new AuthenticatePayload(mUsername, mPassword);
        payload.twoStepCode = mTwoStepCode;
        payload.shouldSendTwoStepSms = mShouldSendTwoStepSMS;
        mDispatcher.dispatch(AuthenticationActionBuilder.newAuthenticateAction(payload));
    }

    protected void configureAccountAfterSuccessfulSignIn() {
        mShouldSendTwoStepSMS = false;

        // Finish this activity if we've authenticated to a Jetpack site
        if (isJetpackAuth() && getActivity() != null) {
            getActivity().setResult(Activity.RESULT_OK);
            getActivity().finish();
            return;
        }
        mDispatcher.dispatch(SiteActionBuilder.newFetchSitesAction());
    }

    private void signInAndFetchBlogListWPOrg() {
        startProgress(getString(R.string.signing_in));
        String url = EditTextUtils.getText(mUrlEditText).trim();

        mSelfhostedPayload = new RefreshSitesXMLRPCPayload();
        mSelfhostedPayload.username = mUsername;
        mSelfhostedPayload.password = mPassword;
        mSelfhostedPayload.url = url;
        // Self Hosted don't have any "Authentication" request, try to list sites with user/password
        mDispatcher.dispatch(AuthenticationActionBuilder.newDiscoverEndpointAction(mSelfhostedPayload));
    }

    private boolean checkNetworkConnectivity() {
        if (!NetworkUtils.isNetworkAvailable(getActivity())) {
            FragmentTransaction ft = getFragmentManager().beginTransaction();
            SignInDialogFragment nuxAlert;
            nuxAlert = SignInDialogFragment.newInstance(getString(R.string.no_network_title),
                    getString(R.string.no_network_message),
                    R.drawable.noticon_alert_big,
                    getString(R.string.cancel));
            ft.add(nuxAlert, "alert");
            ft.commitAllowingStateLoss();
            return false;
        }
        return true;
    }

    protected void signIn() {
        if (!isUserDataValid() || !checkNetworkConnectivity()) {
            return;
        }

        mUsername = EditTextUtils.getText(mUsernameEditText).trim();
        mPassword = EditTextUtils.getText(mPasswordEditText).trim();
        mTwoStepCode = EditTextUtils.getText(mTwoStepEditText).trim();
        if (mUsername.startsWith("@")) {
            mUsername = mUsername.substring(1, mUsername.length());
        }
        if (isWPComLogin()) {
            AppLog.i(T.NUX, "User tries to sign in on WordPress.com with username: " + mUsername);
            signInAndFetchBlogListWPCom();
        } else {
            String selfHostedUrl = EditTextUtils.getText(mUrlEditText).trim();
            AppLog.i(T.NUX, "User tries to sign in on Self Hosted: " + selfHostedUrl + " with username: " + mUsername);
            signInAndFetchBlogListWPOrg();
        }
    }

    private void requestSMSTwoStepCode() {
        if (!isAdded()) return;

        ToastUtils.showToast(getActivity(), R.string.two_step_sms_sent);
        mTwoStepEditText.setText("");
        mShouldSendTwoStepSMS = true;

        signIn();
    }

    private final OnClickListener mSignInClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            signIn();
        }
    };

    @Override
    public void afterTextChanged(Editable s) {
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (fieldsFilled()) {
            mSignInButton.setEnabled(true);
        } else {
            mSignInButton.setEnabled(false);
        }
        mPasswordEditText.setError(null);
        mUsernameEditText.setError(null);
        mTwoStepEditText.setError(null);
    }

    private boolean fieldsFilled() {
        return EditTextUtils.getText(mUsernameEditText).trim().length() > 0
               && (mPasswordLayout.getVisibility() == View.GONE || EditTextUtils.getText(mPasswordEditText).trim().length() > 0)
               && (mTwoStepLayout.getVisibility() == View.GONE || EditTextUtils.getText(mTwoStepEditText).trim().length() > 0);
    }

    protected boolean isUserDataValid() {
        final String username = EditTextUtils.getText(mUsernameEditText).trim();
        final String password = EditTextUtils.getText(mPasswordEditText).trim();
        boolean retValue = true;

        if (password.isEmpty()) {
            mPasswordEditText.setError(getString(R.string.required_field));
            mPasswordEditText.requestFocus();
            retValue = false;
        }

        if (username.isEmpty()) {
            mUsernameEditText.setError(getString(R.string.required_field));
            mUsernameEditText.requestFocus();
            retValue = false;
        }

        return retValue;
    }

    private void showPasswordError(int messageId) {
        mPasswordEditText.setError(getString(messageId));
        mPasswordEditText.requestFocus();
    }

    private void showUsernameError(int messageId) {
        mUsernameEditText.setError(getString(messageId));
        mUsernameEditText.requestFocus();
    }

    private void showUrlError(int messageId) {
        mUrlEditText.setError(getString(messageId));
        mUrlEditText.requestFocus();
    }

    private void showTwoStepCodeError(int messageId) {
        mTwoStepEditText.setError(getString(messageId));
        mTwoStepEditText.requestFocus();
    }

    protected boolean specificShowError(int messageId) {
        switch (getErrorType(messageId)) {
            case USERNAME:
            case PASSWORD:
                showPasswordError(messageId);
                showUsernameError(messageId);
                return true;
            default:
                return false;
        }
    }

    protected void startProgress(String message) {
        mProgressBarSignIn.setVisibility(View.VISIBLE);
        mProgressTextSignIn.setVisibility(View.VISIBLE);
        mSignInButton.setVisibility(View.INVISIBLE);
        mProgressBarSignIn.setEnabled(false);
        mProgressTextSignIn.setText(message);
        mUsernameEditText.setEnabled(false);
        mPasswordEditText.setEnabled(false);
        mTwoStepEditText.setEnabled(false);
        mUrlEditText.setEnabled(false);
        mAddSelfHostedButton.setEnabled(false);
        mCreateAccountButton.setEnabled(false);
        mForgotPassword.setEnabled(false);
    }

    protected void endProgress() {
        mProgressBarSignIn.setVisibility(View.INVISIBLE);
        mProgressTextSignIn.setVisibility(View.INVISIBLE);
        mSignInButton.setVisibility(View.VISIBLE);
        mUsernameEditText.setEnabled(true);
        mPasswordEditText.setEnabled(true);
        mTwoStepEditText.setEnabled(true);
        mUrlEditText.setEnabled(true);
        mAddSelfHostedButton.setEnabled(true);
        mCreateAccountButton.setEnabled(true);
        mForgotPassword.setEnabled(true);
    }

    private void askForHttpAuthCredentials(@NonNull final String url) {
        // Prompt for http credentials
        AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
        alert.setTitle(R.string.http_authorization_required);

        View httpAuth = getActivity().getLayoutInflater().inflate(R.layout.alert_http_auth, null);
        final EditText usernameEditText = (EditText) httpAuth.findViewById(R.id.http_username);
        final EditText passwordEditText = (EditText) httpAuth.findViewById(R.id.http_password);
        alert.setView(httpAuth);
        alert.setPositiveButton(R.string.sign_in, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                mHttpUsername = EditTextUtils.getText(usernameEditText);
                mHttpPassword = EditTextUtils.getText(passwordEditText);
                mHTTPAuthManager.addHTTPAuthCredentials(mHttpUsername, mHttpPassword, url, null);
                signInAndFetchBlogListWPOrg();
            }
        });

        alert.show();
        endProgress();
    }

    protected void handleInvalidUsernameOrPassword(int messageId) {
        mErroneousLogInCount += 1;
        if (mErroneousLogInCount >= WPCOM_ERRONEOUS_LOGIN_THRESHOLD) {
            // Clear previous errors
            mPasswordEditText.setError(null);
            mUsernameEditText.setError(null);
            showInvalidUsernameOrPasswordDialog();
        } else {
            showPasswordError(messageId);
            showUsernameError(messageId);
        }
        endProgress();
    }

    private void showAuthError(AuthenticationErrorType error, String errorMessage) {
        switch (error) {
            case INCORRECT_USERNAME_OR_PASSWORD:
                handleInvalidUsernameOrPassword(R.string.username_or_password_incorrect);
                break;
            case INVALID_OTP:
                showTwoStepCodeError(R.string.invalid_verification_code);
                break;
            case NEEDS_2FA:
                setTwoStepAuthVisibility(true);
                break;
            case INVALID_REQUEST:
                // TODO: STORES: could be specific?
            default:
                // For all other kind of error, show a dialog with API Response error message
                AppLog.e(T.NUX, "Server response: " + errorMessage);
                showGenericErrorDialog(errorMessage);
                break;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_IS_SELF_HOSTED, mSelfHosted);
    }

    @Override
    public void onStart() {
        super.onStart();
        // Autofill username / password if string fields are set (only usefull after an error in sign up).
        // This can't be done in onCreateView
        if (mUsername != null) {
            mUsernameEditText.setText(mUsername);
        }
        if (mPassword != null) {
            mPasswordEditText.setText(mPassword);
        }
        mDispatcher.register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        mDispatcher.unregister(this);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == NewUserFragment.NEW_USER && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                // Text views will be populated by username/password if these fields are set
                mUsername = data.getStringExtra("username");
                mPassword = data.getStringExtra("password");
            }
        }
    }

    // OnChanged events

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAccountChanged(OnAccountChanged event) {
        AppLog.i(T.NUX, event.toString());
        mAccountSettingsFetched |= event.causeOfChange == AccountAction.FETCH_SETTINGS;
        mAccountFetched |= event.causeOfChange == AccountAction.FETCH_ACCOUNT;
        // Finish activity if sites have been fetched
        if (mSitesFetched && mAccountSettingsFetched && mAccountFetched) {
            updateMigrationStatusIfNeeded();
            finishCurrentActivity();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAuthenticationChanged(OnAuthenticationChanged event) {
        AppLog.i(T.NUX, event.toString());
        if (event.isError()) {
            showAuthError(event.error.type, event.error.message);
            updateMigrationStatusIfNeeded();
            endProgress();
            return;
        }
        mErroneousLogInCount = 0;
        if (mAccountStore.hasAccessToken()) {
            // On WordPress.com login, configure Simperium
            AppLog.i(T.NOTIFS, "Configuring Simperium");
            SimperiumUtils.configureSimperium(getContext().getApplicationContext(),
                    mAccountStore.getAccessToken());
            // Fetch user infos
            mDispatcher.dispatch(AccountActionBuilder.newFetchAccountAction());
            mDispatcher.dispatch(AccountActionBuilder.newFetchSettingsAction());
            // Fetch sites
            mDispatcher.dispatch(SiteActionBuilder.newFetchSitesAction());
            // Setup legacy access token storage
            OAuthAuthenticator.sAccessToken = mAccountStore.getAccessToken();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSiteChanged(OnSiteChanged event) {
        AppLog.i(T.NUX, event.toString());

        if (event.isError()) {
            return;
        }

        // Login Successful
        trackAnalyticsSignIn();
        mSitesFetched = true;
        // Finish activity if account settings have been fetched or if it's a wporg site
        if ((mAccountSettingsFetched && mAccountFetched) || !isWPComLogin()) {
            updateMigrationStatusIfNeeded();
            finishCurrentActivity();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDiscoverySucceeded(AccountStore.OnDiscoveryResponse event) {
        if (event.isError()) {
            handleDiscoveryError(event.error, event.failedEndpoint);
            return;
        }
        AppLog.i(T.NUX, "Discovery succeeded, endpoint: " + event.xmlRpcEndpoint);
        mSelfhostedPayload.url = event.xmlRpcEndpoint;
        mDispatcher.dispatch(SiteActionBuilder.newFetchSitesXmlRpcAction(mSelfhostedPayload));
    }

    public void handleDiscoveryError(DiscoveryError error, String failedEndpoint) {
        AppLog.e(T.API, "Discover error: " + error);
        if (!isAdded()) {
            return;
        }
        endProgress();
        switch (error) {
            case ERRONEOUS_SSL_CERTIFICATE:
                mSelfhostedPayload.url = failedEndpoint;
                showSSLWarningDialog();
                break;
            case HTTP_AUTH_REQUIRED:
                askForHttpAuthCredentials(failedEndpoint);
                break;
            case WORDPRESS_COM_SITE:
                signInAndFetchBlogListWPCom();
                break;
            case XMLRPC_BLOCKED:
            case XMLRPC_FORBIDDEN:
                showXMLRPCSupportDialog();
                break;
            case NO_SITE_ERROR:
                showUrlError(R.string.invalid_site_url_message);
                break;
            case INVALID_URL:
                showUrlError(R.string.invalid_url_message);
                break;
            case MISSING_XMLRPC_METHOD:
                showGenericErrorDialog(getResources().getString(R.string.xmlrpc_missing_method_error));
                break;
            case GENERIC_ERROR:
                showGenericErrorDialog(getResources().getString(R.string.login_failed_message));
                break;
        }
    }

    private void showSSLWarningDialog() {
        SelfSignedSSLUtils.showSSLWarningDialog(getActivity(), mMemorizingTrustManager, new Callback() {
                @Override
                public void certificateTrusted() {
                    if (mSelfhostedPayload == null) {
                        return;
                    }
                    // retry login with the same parameters
                    startProgress(getString(R.string.signing_in));
                    mDispatcher.dispatch(AuthenticationActionBuilder.newDiscoverEndpointAction(mSelfhostedPayload));
                }});
    }

    private void showNuxAlertDialog(String message, int numButtons, int buttonLabel0, int buttonLabel1,
                                    int buttonLabel2, int action1, int action2, Bundle args) {
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        SignInDialogFragment nuxAlert = SignInDialogFragment.newInstance(
                getString(org.wordpress.android.R.string.nux_cannot_log_in), message, R.drawable.noticon_alert_big,
                numButtons, getString(buttonLabel0), getString(buttonLabel1), getString(buttonLabel2), action1, action2);

        if (args != null) {
            Bundle dialogArgs = nuxAlert.getArguments();
            dialogArgs.putAll(args);
            nuxAlert.setArguments(dialogArgs);
        }

        ft.add(nuxAlert, "alert");
        ft.commitAllowingStateLoss();
    }

    private void showGenericErrorDialog(String errorMessage) {
        showNuxAlertDialog(errorMessage, 3, R.string.cancel, R.string.contact_us, R.string.reader_title_applog,
                SignInDialogFragment.ACTION_OPEN_SUPPORT_CHAT, SignInDialogFragment.ACTION_OPEN_APPLICATION_LOG, null);
    }

    private void showXMLRPCSupportDialog() {
        Bundle args = new Bundle();
        args.putString(SignInDialogFragment.ARG_OPEN_URL_PARAM, XMLRPC_SUPPORT_URL);
        args.putString(ENTERED_URL_KEY, EditTextUtils.getText(mUrlEditText));
        args.putString(ENTERED_USERNAME_KEY, EditTextUtils.getText(mUsernameEditText));
        showNuxAlertDialog(getString(R.string.xmlrpc_discovery_error), 3, R.string.cancel, R.string.faq_button,
                R.string.contact_us, SignInDialogFragment.ACTION_OPEN_URL,
                SignInDialogFragment.ACTION_OPEN_SUPPORT_CHAT, args);
    }

    protected void showInvalidUsernameOrPasswordDialog() {
        Bundle args = new Bundle();
        args.putString(SignInDialogFragment.ARG_OPEN_URL_PARAM, getForgotPasswordURL());
        args.putString(SignInDialogFragment.ARG_OPEN_URL2_PARAM, "https://apps.wordpress.com/support/#faq-ios-gs2");
        args.putString(ENTERED_URL_KEY, EditTextUtils.getText(mUrlEditText));
        args.putString(ENTERED_USERNAME_KEY, EditTextUtils.getText(mUsernameEditText));
        showNuxAlertDialog(getString(R.string.username_or_password_incorrect_hint), 3, R.string.cancel,
                R.string.forgot_password, R.string.faq_button, SignInDialogFragment.ACTION_OPEN_URL,
                SignInDialogFragment.ACTION_OPEN_URL2, args);
    }

    private void updateMigrationStatusIfNeeded() {
        if (AppPrefs.wasAccessTokenMigrated()) {
            AppPrefs.setAccessTokenMigrated(false);
            endProgress();
        }
    }
}
