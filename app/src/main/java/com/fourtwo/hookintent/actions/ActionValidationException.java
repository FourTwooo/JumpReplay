package com.fourtwo.hookintent.actions;

import android.content.Context;

/** Keep model validation independent of a UI Context while localizing errors at display time. */
public final class ActionValidationException extends IllegalArgumentException {
    private final int message;
    public ActionValidationException(int message) { super("Invalid quick action: "+message);this.message=message; }
    public static String text(Context context,Throwable error) {
        if(error instanceof ActionValidationException) return context.getString(((ActionValidationException)error).message);
        if(error instanceof com.fourtwo.hookintent.utils.ShortcutParameterReplacer.ValidationException) {
            com.fourtwo.hookintent.utils.ShortcutParameterReplacer.ValidationException failure=
                    (com.fourtwo.hookintent.utils.ShortcutParameterReplacer.ValidationException)error;
            int resource;
            switch(failure.reason) {
                case EMPTY_INTENT: resource=com.fourtwo.hookintent.R.string.action_error_empty_intent;break;
                case EMPTY_PARAMETER: resource=com.fourtwo.hookintent.R.string.action_error_empty_parameter;break;
                case DUPLICATE_PARAMETER: resource=com.fourtwo.hookintent.R.string.action_error_duplicate_parameter;break;
                case MISSING_PARAMETER: resource=com.fourtwo.hookintent.R.string.action_error_missing_parameter;break;
                case VALUE_COUNT: resource=com.fourtwo.hookintent.R.string.action_error_parameter_count;break;
                default: resource=com.fourtwo.hookintent.R.string.action_error_authority_parameter;
            }
            return context.getString(resource,failure.parameter);
        }
        String detail=error.getLocalizedMessage();return detail==null ? error.getClass().getSimpleName() : detail;
    }
}
