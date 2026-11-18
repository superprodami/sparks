(opened => {
    const picker = document.querySelector('.el-pagination .el-pagination__sizes .el-select');
    if (!picker) return 'unavailable';
    if (parseInt(picker.querySelector('input')?.value, 10) === 50) return 'selected';
    const options = [...document.querySelectorAll('.el-select-dropdown__item')]
        .filter(item => item.getClientRects().length && getComputedStyle(item).visibility !== 'hidden');
    const option = options.find(item => /^50\s*条\s*\/\s*页$/.test(item.textContent.trim()));
    if (option) {
        option.click();
        return 'selected';
    }
    if (!opened) {
        picker.click();
        return 'opening';
    }
    if (!options.length) return 'opening';
    picker.click();
    return 'unavailable';
})
