package top.bogey.touch_tool.bean.action;

import androidx.annotation.AttrRes;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

public class ActionCheckResult {
    private final List<Result> results = new ArrayList<>();

    public void addResult(ResultType type, @StringRes int msg) {
        results.add(new Result(type, msg));
    }

    public void merge(ActionCheckResult result) {
        results.addAll(result.results);
    }

    public void merge(ActionCheckResult checkResult, ResultType minType) {
        for (Result result : checkResult.results) {
            if (result.type.ordinal() < minType.ordinal()) continue;
            results.add(result);
        }
    }

    public boolean hasError() {
        return results.stream().anyMatch(result -> result.type == ResultType.ERROR);
    }

    public Result getError() {
        return results.stream().filter(result -> result.type == ResultType.ERROR).findFirst().orElse(null);
    }

    public boolean hasWarning() {
        return results.stream().anyMatch(result -> result.type == ResultType.WARNING);
    }

    public Result getWarning() {
        return results.stream().filter(result -> result.type == ResultType.WARNING).findFirst().orElse(null);
    }

    public Result getResult() {
        return results.stream().findFirst().orElse(null);
    }

    public Result getImportantResult() {
        Result result = getError();
        if (result == null) result = getWarning();
        if (result == null) result = getResult();
        return result;
    }

    public record Result(ResultType type, @StringRes int msg) {
    }

    public enum ResultType {
        INFO,
        WARNING,
        ERROR;


        public @AttrRes int getBackgroundColor() {
            return switch (this) {
                case WARNING -> com.google.android.material.R.attr.colorTertiary;
                case ERROR -> androidx.appcompat.R.attr.colorError;
                default -> androidx.appcompat.R.attr.colorPrimary;
            };
        }

        public @AttrRes int getTextColor() {
            return switch (this) {
                case WARNING -> com.google.android.material.R.attr.colorOnTertiary;
                case ERROR -> com.google.android.material.R.attr.colorOnError;
                default -> com.google.android.material.R.attr.colorOnPrimary;
            };
        }
    }
}
