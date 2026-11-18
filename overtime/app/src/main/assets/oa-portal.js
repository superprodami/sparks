(() => {
    const documents = [document];
    for (let i = 0; i < documents.length; i++) {
        for (const frame of documents[i].querySelectorAll('iframe, frame')) {
            if (frame.contentDocument) documents.push(frame.contentDocument);
        }
    }
    for (const doc of documents) {
        const matches = item => item.getClientRects().length && item.textContent.trim() === '人事系统';
        const link = [...doc.querySelectorAll('a, button, [role="menuitem"], [onclick]')].find(matches);
        const item = link || [...doc.querySelectorAll('li, span, div')].reverse().find(matches);
        if (!item) continue;
        item.click();
        return true;
    }
    return false;
})()
