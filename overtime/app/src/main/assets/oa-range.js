((from, through, step) => {
    const visible = node => node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
    const picker = [...document.querySelectorAll('.el-date-range-picker')].find(visible);
    const popover = [...document.querySelectorAll('.el-popover')].find(node =>
        visible(node) && node.querySelector('.el-range-input'));
    if (!popover && !picker) {
        const range = /^\d{4}-\d{2}-\d{2}\s+[-–~至]\s+\d{4}-\d{2}-\d{2}$/;
        const rangeInput = [...document.querySelectorAll('.el-range-input')].find(visible);
        const reference = [...document.querySelectorAll('.el-popover__reference, .el-input__inner')]
            .find(node => visible(node) && range.test((node.value || node.textContent).trim()))
            || rangeInput?.closest('.el-popover__reference') || rangeInput;
        if (!reference) return 'no-trigger';
        reference.click();
        return 'open-range';
    }

    if (step === 'confirm') {
        if (!popover) return 'applied';
        const confirm = [...popover.querySelectorAll('button')].find(button =>
            visible(button) && button.textContent.trim() === '确认');
        if (!confirm) return 'no-confirm';
        confirm.click();
        return 'applied';
    }

    if (!picker) {
        const dateMode = [...popover.querySelectorAll('label')].find(label => label.textContent.trim() === '日期');
        if (dateMode && !dateMode.classList.contains('is-checked')) dateMode.click();
        popover.querySelector('.el-range-input').click();
        return step;
    }

    const target = step === 'end' ? through : from;
    const [year, month, day] = target.split('-').map(Number);
    const monthIndex = year * 12 + month;
    const panels = [...picker.querySelectorAll('.el-date-range-picker__content')].filter(visible).map(node => {
        const header = node.querySelector('.el-date-range-picker__header');
        const match = header?.textContent.match(/(\d{4})\s*年\s*(\d{1,2})\s*月/);
        return {node, header, month: match ? Number(match[1]) * 12 + Number(match[2]) : 0};
    }).sort((left, right) => left.month - right.month);
    if (!panels.length || panels.some(panel => !panel.month)) return 'no-calendar';
    const panel = panels.find(item => item.month === monthIndex);
    if (panel) {
        const cell = [...panel.node.querySelectorAll('.el-date-table td.available')].find(item =>
            !item.matches('.prev-month, .next-month, .disabled') && item.textContent.trim() === String(day));
        if (!cell) return 'no-date';
        (cell.querySelector('span') || cell).click();
        return step === 'end' ? 'confirm' : 'end';
    }

    const edge = monthIndex < panels[0].month ? panels[0] : panels[panels.length - 1];
    const earlier = monthIndex < edge.month;
    const direction = earlier ? 'left' : 'right';
    const distance = Math.abs(edge.month - monthIndex);
    const arrow = edge.header.querySelector((distance >= 12 ? '.d-arrow-' : '.arrow-') + direction);
    if (!arrow || arrow.disabled) return 'no-navigation';
    arrow.click();
    return step;
})
