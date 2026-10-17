package top.bogey.touch_tool.ui.blueprint.card;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.LayoutInflater;

import com.google.android.material.button.MaterialButton;

import java.util.Arrays;
import java.util.List;

import top.bogey.touch_tool.bean.action.Action;
import top.bogey.touch_tool.bean.action.ActionCheckResult;
import top.bogey.touch_tool.bean.pin.Pin;
import top.bogey.touch_tool.bean.task.Task;
import top.bogey.touch_tool.databinding.CardNormalBinding;
import top.bogey.touch_tool.ui.blueprint.pin.PinBottomView;
import top.bogey.touch_tool.ui.blueprint.pin.PinLeftView;
import top.bogey.touch_tool.ui.blueprint.pin.PinRightView;
import top.bogey.touch_tool.ui.blueprint.pin.PinTopView;
import top.bogey.touch_tool.ui.blueprint.pin.PinView;
import top.bogey.touch_tool.utils.DisplayUtil;

@SuppressLint("ViewConstructor")
public class NormalActionCard extends ActionCard {
    private CardNormalBinding binding;

    public NormalActionCard(Context context, Task task, Action action) {
        super(context, task, action);
    }

    @Override
    public void init() {
        binding = CardNormalBinding.inflate(LayoutInflater.from(getContext()), this, true);

        initCardInfo(binding.icon, binding.title, binding.des);
        initEditDesc(binding.editButton, binding.des);
        initDelete(binding.removeButton);
        initCopy(binding.copyButton);
        initLock(binding.lockButton);
        initExpand(binding.expandButton);
        initPosView(binding.position);
    }

    @Override
    public void refreshCardInfo() {
        initCardInfo(binding.icon, binding.title, binding.des);
    }

    @Override
    public void refreshCardLockState() {
        initLock(binding.lockButton);
    }

    @Override
    public boolean check() {
        ActionCheckResult result = new ActionCheckResult();
        action.check(result, task);
        ActionCheckResult.Result importantResult = result.getImportantResult();
        if (importantResult != null) {
            binding.errorText.setVisibility(VISIBLE);
            binding.errorText.setText(importantResult.msg());
            binding.errorText.setBackgroundColor(DisplayUtil.getAttrColor(getContext(), importantResult.type().getBackgroundColor()));
            binding.errorText.setTextColor(DisplayUtil.getAttrColor(getContext(), importantResult.type().getTextColor()));
        } else {
            binding.errorText.setVisibility(GONE);
        }
        return importantResult == null || importantResult.type() != ActionCheckResult.ResultType.ERROR;
    }

    @Override
    public void addPinView(Pin pin, int offset) {
        PinView pinView;
        if (pin.isOut()) {
            if (pin.isVertical()) {
                pinView = new PinBottomView(getContext(), this, pin);
                binding.bottomBox.addView(pinView, binding.bottomBox.getChildCount() - offset);
            } else {
                pinView = new PinRightView(getContext(), this, pin);
                binding.outBox.addView(pinView, binding.outBox.getChildCount() - offset);
            }
        } else {
            if (pin.isVertical()) {
                pinView = new PinTopView(getContext(), this, pin);
                binding.topBox.addView(pinView, binding.topBox.getChildCount() - offset);
            } else {
                pinView = new PinLeftView(getContext(), this, pin);
                binding.inBox.addView(pinView, binding.inBox.getChildCount() - offset);
            }
        }
        pinView.expand(action.getExpandType());
        pinViews.put(pin.getId(), pinView);
    }

    @Override
    public boolean isEmptyPosition(float x, float y) {
        float scale = getScaleX();

        List<MaterialButton> buttons = Arrays.asList(binding.editButton, binding.lockButton, binding.expandButton, binding.copyButton, binding.removeButton);
        for (MaterialButton button : buttons) {
            PointF pointF = DisplayUtil.getLocationRelativeToView(button, this);
            float px = pointF.x * scale;
            float py = pointF.y * scale;
            float width = button.getWidth() * scale;
            float height = button.getHeight() * scale;
            if (new RectF(px, py, px + width, py + height).contains(x, y)) return false;
        }
        return super.isEmptyPosition(x, y);
    }
}
