package com.fourtwo.hookintent.ui.detail;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.StringRes;
import androidx.core.util.Pair;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.data.Constants;

import java.util.List;

final class NavigationReplayDiagnostics {
    private NavigationReplayDiagnostics() {}

    static void append(Context context, List<Pair<String, String>> rows, Bundle record) {
        Bundle diagnostics = record.getBundle(Constants.NAVIGATION_REPLAY_DIAGNOSTICS);
        if (diagnostics == null || diagnostics.isEmpty()) {
            rows.add(new Pair<>(context.getString(R.string.navigation_replay_options),
                    context.getString(R.string.navigation_replay_diagnostic_legacy)));
            return;
        }
        add(context, rows, diagnostics.getString("observation"), R.string.navigation_replay_diagnostic_observation);
        add(context, rows, diagnostics.getString("transaction"), R.string.navigation_replay_diagnostic_transaction);
        add(context, rows, diagnostics.getString("host"), R.string.navigation_replay_diagnostic_host);
        add(context, rows, diagnostics.getString("storage"), R.string.navigation_replay_diagnostic_storage);
    }

    private static void add(Context context, List<Pair<String, String>> rows, String reason, @StringRes int label) {
        if (reason == null) return;
        rows.add(new Pair<>(context.getString(label), context.getString(reasonText(reason))));
    }

    @StringRes
    private static int reasonText(String reason) {
        switch (reason) {
            case "observation_depth_limit": return R.string.navigation_replay_observation_depth_limit;
            case "observation_count_limit": return R.string.navigation_replay_observation_count_limit;
            case "proof_unavailable": return R.string.navigation_replay_proof_unavailable;
            case "transaction_not_seen": return R.string.navigation_replay_transaction_not_seen;
            case "transaction_hook_unavailable": return R.string.navigation_replay_transaction_hook_unavailable;
            case "transaction_commit_failed": return R.string.navigation_replay_transaction_commit_failed;
            case "transaction_duplicate": return R.string.navigation_replay_transaction_duplicate;
            case "transaction_expired": return R.string.navigation_replay_transaction_expired;
            case "transaction_source_unresolved": return R.string.navigation_replay_transaction_source_unresolved;
            case "transaction_container_unavailable": return R.string.navigation_replay_transaction_container_unavailable;
            case "transaction_custom_state": return R.string.navigation_replay_transaction_custom_state;
            case "transaction_multiple_destinations": return R.string.navigation_replay_transaction_multiple_destinations;
            case "transaction_unsupported_operation": return R.string.navigation_replay_transaction_unsupported_operation;
            case "transaction_existing_destination": return R.string.navigation_replay_transaction_existing_destination;
            case "transaction_unsupported_arguments": return R.string.navigation_replay_transaction_unsupported_arguments;
            case "transaction_argument_limit": return R.string.navigation_replay_transaction_argument_limit;
            case "transaction_arguments_changed": return R.string.navigation_replay_transaction_arguments_changed;
            case "transaction_inspection_failed": return R.string.navigation_replay_transaction_inspection_failed;
            case "host_no_source": return R.string.navigation_replay_host_no_source;
            case "host_destination_ambiguous": return R.string.navigation_replay_host_destination_ambiguous;
            case "host_call_not_observed": return R.string.navigation_replay_host_call_not_observed;
            case "host_call_expired": return R.string.navigation_replay_host_call_expired;
            case "host_call_ambiguous": return R.string.navigation_replay_host_call_ambiguous;
            case "host_candidate_overflow": return R.string.navigation_replay_host_candidate_overflow;
            case "host_arguments_unsupported": return R.string.navigation_replay_host_arguments_unsupported;
            case "host_no_argument_mapping": return R.string.navigation_replay_host_no_argument_mapping;
            case "host_capture_busy": return R.string.navigation_replay_host_capture_busy;
            case "host_observation_limited": return R.string.navigation_replay_host_observation_limited;
            case "host_destination_already_visible": return R.string.navigation_replay_host_destination_already_visible;
            default: return R.string.navigation_replay_diagnostic_unknown;
        }
    }
}
