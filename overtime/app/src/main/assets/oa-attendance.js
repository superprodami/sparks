(() => {
    const body = document.body;
    if (!body) return {state: 'waiting'};
    if (!location.hash.includes('/Att/SelfDayResult')) {
        if (body.innerText.includes('员工自助')) location.hash = '/Att/SelfDayResult';
        return {state: 'waiting'};
    }

    const datePattern = /^\d{4}-\d{2}-\d{2}$/;
    const displayPattern = /^(\d{4}-\d{2}-\d{2})\s+[-–~至]\s+(\d{4}-\d{2}-\d{2})$/;
    const display = [...body.querySelectorAll('.el-popover__reference, .el-input__inner')]
        .map(node => (node.value || node.textContent).trim().match(displayPattern)).find(Boolean);
    const dates = display ? display.slice(1) : [...body.querySelectorAll('.el-range-input')].map(input => input.value.trim());
    const from = datePattern.test(dates[0]) ? dates[0] : null;
    const through = datePattern.test(dates[1]) ? dates[1] : null;
    const visible = node => node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
    for (const table of body.querySelectorAll('.el-table, table')) {
        const header = [...table.querySelectorAll('thead tr')].find(row => {
            const names = [...row.cells].map(cell => cell.textContent.replace(/\s+/g, ''));
            return ['日期', '上班刷卡', '下班刷卡'].every(name => names.includes(name));
        });
        if (!header) continue;
        const wrapper = header.closest('.el-table') || table;
        if ([...wrapper.querySelectorAll('.el-loading-mask')].some(visible)) return {state: 'waiting'};
        const columns = [...header.cells].map(cell => cell.textContent.replace(/\s+/g, ''));
        const indexes = ['日期', '上班刷卡', '下班刷卡'].map(name => columns.indexOf(name));
        const data = wrapper.querySelector('.el-table__body-wrapper') || wrapper;
        const rows = [];
        for (const row of data.querySelectorAll('tbody tr')) {
            const values = indexes.map(index => row.cells[index]?.textContent.trim() || '');
            if (datePattern.test(values[0])) rows.push(values);
        }
        const empty = wrapper.querySelector('.el-table__empty-text');
        if (rows.length || (empty && visible(empty))) {
            const pagination = body.querySelector('.el-pagination');
            const next = pagination?.querySelector('button.btn-next');
            const size = pagination?.querySelector('.el-pagination__sizes input');
            const pageSize = parseInt(size?.value, 10) || 0;
            return {state: 'ready', rows, from, through, pageSize, hasNext: !!next && !next.disabled};
        }
    }
    return {state: 'waiting'};
})()
