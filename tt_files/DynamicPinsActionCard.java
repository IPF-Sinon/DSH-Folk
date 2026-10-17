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
import top.bogey.touch_tool.bean.pin.pin_objects.PinAdd;
import top.bogey.touch_tool.bean.task.Task;
import top.bogey.touch_tool.databinding.CardDynamicPinsBinding;
import top.bogey.touch_tool.ui.blueprint.pin.PinView;
import top.bogey.touch_tool.utils.DisplayUtil;

@SuppressLint("ViewConstructor")
public class DynamicPinsActionCard extends ActionCard implements IDynamicPinCard {
    private CardDynamicPinsBinding binding;
    // 一个方位一个列表，界面顺序与动作的针脚列表保持一致
    private DynamicPinsActionAdapter topAdapter;
    private DynamicPinsActionAdapter bottomAdapter;
    private DynamicPinsActionAdapter leftAdapter;
    private DynamicPinsActionAdapter rightAdapter;

    public DynamicPinsActionCard(Context context, Task task, Action action) {
        super(context, task, action);
    }

    @Override
    public void init() {
        binding = CardDynamicPinsBinding.inflate(LayoutInflater.from(getContext()), this, true);

        // 一次添加多个针脚的动作，同组的针脚放在同一行一起拖动
        int groupSize = getGroupSize(action);
        topAdapter = new DynamicPinsActionAdapter(this, true, false, groupSize);
        bottomAdapter = new DynamicPinsActionAdapter(this, true, true, groupSize);
        leftAdapter = new DynamicPinsActionAdapter(this, false, false, groupSize);
        rightAdapter = new DynamicPinsActionAdapter(this, false, true, groupSize);
        topAdapter.attachToRecyclerView(binding.topPinBox);
        bottomAdapter.attachToRecyclerView(binding.bottomPinBox);
        leftAdapter.attachToRecyclerView(binding.inPinBox);
        rightAdapter.attachToRecyclerView(binding.outPinBox);

        initCardInfo(binding.icon, binding.title, binding.des);
        initEditDesc(binding.editButton, binding.des);
        initDelete(binding.removeButton);
        initCopy(binding.copyButton);
        initLock(binding.lockButton);
        initExpand(binding.expandButton);
        initPosView(binding.position);
    }

    // 一次添加动作会添几个针脚，成组的针脚要并到同一行
    private int getGroupSize(Action action) {
        for (Pin pin : action.getPins()) {
            if (pin.getValue() instanceof PinAdd pinAdd) {
                return Math.max(1, pinAdd.getPins().size());
            }
        }
        return 1;
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
        // 先建好视图，再按动作的针脚顺序重建列表，保证界面和数据一致
        PinView pinView = getAdapter(pin).addPin(pin);
        pinView.expand(action.getExpandType());
        pinViews.put(pin.getId(), pinView);
        refreshRows();
    }

    @Override
    public void removePinView(Pin pin) {
        super.removePinView(pin);
        refreshRows();
    }

    // 展开模式切换会成片隐藏针脚，藏光了的那条列表会被压成 0 高并回收掉针脚视图，
    // 只改针脚可见性不会再触发列表布局，所以这里让四个列表重新量一次
    @Override
    public void setExpandType(Action.ExpandType expandType) {
        super.setExpandType(expandType);
        requestPinBoxLayout();
    }

    @Override
    public void expand() {
        super.expand();
        requestPinBoxLayout();
    }

    private void requestPinBoxLayout() {
        binding.topPinBox.requestLayout();
        binding.bottomPinBox.requestLayout();
        binding.inPinBox.requestLayout();
        binding.outPinBox.requestLayout();
    }

    // 按动作的针脚列表重建各方位列表的顺序
    private void refreshRows() {
        if (topAdapter == null) return;
        topAdapter.refreshRows();
        bottomAdapter.refreshRows();
        leftAdapter.refreshRows();
        rightAdapter.refreshRows();
    }

    private DynamicPinsActionAdapter getAdapter(Pin pin) {
        if (pin.isVertical()) {
            return pin.isOut() ? bottomAdapter : topAdapter;
        } else {
            return pin.isOut() ? rightAdapter : leftAdapter;
        }
    }

    // 抑制列表滚动，让针脚连线能够生效
    @Override
    public void suppressLayout() {
        binding.topPinBox.suppressLayout(true);
        binding.bottomPinBox.suppressLayout(true);
        binding.inPinBox.suppressLayout(true);
        binding.outPinBox.suppressLayout(true);
        postDelayed(() -> {
            binding.topPinBox.suppressLayout(false);
            binding.bottomPinBox.suppressLayout(false);
            binding.inPinBox.suppressLayout(false);
            binding.outPinBox.suppressLayout(false);
        }, 100);
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
