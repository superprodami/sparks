((from, through) => {
    if (location.hostname !== 'ehr.raynen.cn' || location.port !== '3039') return {state: 'waiting'};
    if (window.overtimeDayQuery) return window.overtimeDayQuery;
    const entry = [...document.querySelectorAll('script[type="module"][src]')].find(script => {
        const url = new URL(script.src);
        return url.origin === location.origin && /\/static\/js\/main\.[\w-]+\.js$/.test(url.pathname);
    });
    if (!entry || typeof window.HCM_PC !== 'string') return {state: 'waiting'};
    window.overtimeDayQuery = {state: 'pending'};
    (async () => {
        // Use the same authenticated client and personal grid as the employee portal.
        const {br: client} = await import(entry.src);
        const service = window.HCM_PC + 'services/app/';
        const response = await client.post(service + 'SelfDayResulft/PostGridBySelfDayResulft', {
            radio: '', beginDate: from, endDate: through, yearMonth: through.slice(0, 7), isYearMonth: false
        });
        const grid = response.data.result;
        const fields = ['日期', '上班刷卡', '下班刷卡'].map(name => grid.columns.find(c => c.label === name).prop);
        const total = grid.pageable.total;
        let data = grid.dataSource;
        if (data.length < total) {
            const page = await client.get(service + 'Grid/GetPageData', {
                params: {gridKey: grid.gridKey, currentPage: 1, pageSize: total}
            });
            data = page.data.result.dataSource;
        }
        if (!Number.isInteger(total) || data.length !== total) throw Error('incomplete grid');
        const rows = data.map(row => fields.map(field => {
            if (!(field in row)) throw Error('missing column');
            return String(row[field] ?? '').trim();
        }));
        if (rows.some(([day]) => !/^\d{4}-\d{2}-\d{2}$/.test(day) || day < from || day > through))
            throw Error('unexpected date');
        window.overtimeDayQuery = {state: 'ready', rows, from, through};
    })().catch(() => { window.overtimeDayQuery = {state: 'unavailable'}; });
    return window.overtimeDayQuery;
})
