(() => {
  const data = window.CARE || {};
  const teal = '#398477', pale = '#dcebe5', coral = '#c77a62', grid = '#e8efec', ink = '#718485';
  const charts = [];
  const chart = (id, config) => {
    const canvas = document.getElementById(id);
    if (canvas && window.Chart) charts.push(new Chart(canvas, config));
  };
  const common = { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false }, tooltip: { backgroundColor: '#173f3e', padding: 10, titleFont: { family: 'DM Sans' }, bodyFont: { family: 'DM Sans' } } } };
  const histogram = (values, bins = 12) => {
    const nums = (values || []).map(Number).filter(Number.isFinite);
    if (!nums.length) return { labels: ['No data'], values: [0] };
    const min = Math.min(...nums), max = Math.max(...nums), width = (max - min) / bins || 1;
    const counts = Array(bins).fill(0);
    nums.forEach(v => counts[Math.min(bins - 1, Math.floor((v - min) / width))]++);
    return { labels: counts.map((_, i) => `${(min + width * i).toFixed(1)}–${(min + width * (i + 1)).toFixed(1)}`), values: counts };
  };
  const barConfig = (labels, values, color = teal, horizontal = false) => ({ type: 'bar', data: { labels, datasets: [{ data: values, backgroundColor: color, borderRadius: 5, maxBarThickness: 28 }] }, options: { ...common, indexAxis: horizontal ? 'y' : 'x', scales: { x: { grid: { display: horizontal, color: grid }, ticks: { color: ink, font: { size: 9 } }, border: { display: false } }, y: { grid: { display: !horizontal, color: grid }, ticks: { color: ink, font: { size: 9 } }, border: { display: false }, beginAtZero: true } } } });
  const coc = histogram(data.coc, 12); chart('cocChart', barConfig(coc.labels, coc.values));
  const age = histogram(data.age, 12); chart('ageChart', barConfig(age.labels, age.values, pale));
  const providers = histogram(data.providers, 10); chart('providerChart', barConfig(providers.labels, providers.values));
  const fragmentation = histogram(data.fragmentation, 10); chart('fragmentationChart', barConfig(fragmentation.labels, fragmentation.values, coral));
  if (data.utilization) chart('utilizationChart', barConfig(['Emergency visits', 'Outpatient visits', 'Hospitalizations'], data.utilization.map(Number), teal));
  if (data.trend && data.trend.length) chart('cocTrendChart', { type: 'line', data: { labels: data.trend.map(p => p.windowStart), datasets: [{ data: data.trend.map(p => Number(p.coc)), borderColor: teal, backgroundColor: '#39847720', fill: true, pointRadius: 3, borderWidth: 2, tension: .25 }] }, options: { ...common, scales: { x: { grid: { display: false }, ticks: { color: ink, font: { size: 9 } } }, y: { min: 0, max: 1, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } } } } });
  if (data.outcomes) chart('outcomeChart', { type: 'doughnut', data: { labels: ['Outcome 0', 'Outcome 1'], datasets: [{ data: data.outcomes, backgroundColor: [pale, coral], borderWidth: 0, hoverOffset: 5 }] }, options: { ...common, cutout: '70%', plugins: { ...common.plugins, legend: { display: true, position: 'bottom', labels: { color: ink, boxWidth: 9, font: { size: 10 } } } } } });
  if (data.classes) chart('classChart', { ...barConfig(['Negative', 'Positive'], data.classes, [pale, coral]), options: { ...barConfig(['Negative', 'Positive'], data.classes).options, plugins: { ...common.plugins, legend: { display: false } } } });
  if (data.importance) {
    const entries = Object.entries(data.importance).sort((a, b) => Number(b[1]) - Number(a[1])).slice(0, 14).reverse();
    chart('importanceChart', barConfig(entries.map(x => x[0].replaceAll('_', ' ')), entries.map(x => Number(x[1])), teal, true));
  }
  if (data.roc) chart('rocChart', { type: 'line', data: { datasets: [{ label: 'ROC', data: data.roc.map(p => ({ x: Number(p[0]), y: Number(p[1]) })), borderColor: teal, backgroundColor: '#39847720', pointRadius: 0, borderWidth: 2, fill: false }, { label: 'Reference', data: [{x:0,y:0},{x:1,y:1}], borderColor: '#b8c4c0', borderDash: [5,5], pointRadius: 0, borderWidth: 1 }] }, options: { ...common, parsing: false, scales: { x: { type: 'linear', min: 0, max: 1, title: { display: true, text: 'False positive rate', color: ink, font: { size: 10 } }, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } }, y: { min: 0, max: 1, title: { display: true, text: 'True positive rate', color: ink, font: { size: 10 } }, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } } } } });
  if (data.pr) chart('prChart', { type: 'line', data: { datasets: [{ label: 'Precision–recall', data: data.pr.map(p => ({ x: Number(p[0]), y: Number(p[1]) })), borderColor: coral, backgroundColor: '#c77a6218', pointRadius: 0, borderWidth: 2, fill: false }] }, options: { ...common, parsing: false, scales: { x: { type: 'linear', min: 0, max: 1, title: { display: true, text: 'Recall', color: ink, font: { size: 10 } }, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } }, y: { min: 0, max: 1, title: { display: true, text: 'Precision', color: ink, font: { size: 10 } }, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } } } } });
  if (data.scatter) chart('riskScatter', { type: 'scatter', data: { datasets: [{ data: data.scatter.filter(p => Number.isFinite(Number(p.coc))).map(p => ({ x: Number(p.coc), y: Number(p.risk) })), backgroundColor: '#39847775', pointRadius: 3 }] }, options: { ...common, scales: { x: { title: { display: true, text: 'COC score', color: ink, font: { size: 10 } }, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } }, y: { title: { display: true, text: 'Predicted probability', color: ink, font: { size: 10 } }, min: 0, max: 1, grid: { color: grid }, ticks: { color: ink, font: { size: 9 } } } } } });
  window.addEventListener('beforeunload', () => charts.forEach(c => c.destroy()), { once: true });
})();
