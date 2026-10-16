import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api } from './api';
import type { Product, ProductInput } from './types';

const emptyForm: ProductInput = { name: '', url: '', targetPrice: '', currency: 'USD', priceSelector: '' };
const currency = (amount: number | null, code: string) => amount == null
  ? '—'
  : new Intl.NumberFormat(undefined, { style: 'currency', currency: code }).format(amount);
const date = (value: string | null) => value
  ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : 'Not checked yet';
const isDirectProductPage = (value: string) => {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    if (host.startsWith('amazon.') || host.includes('.amazon.')) return /\/(?:dp|gp\/product)\/[a-z0-9]{10}(?:\/|$)/i.test(url.pathname);
    return url.pathname !== '/' && url.pathname !== '';
  } catch { return false; }
};

export default function App() {
  const [products, setProducts] = useState<Product[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [sortMode, setSortMode] = useState<'recent' | 'low' | 'high'>('recent');
  const [pushEnabled, setPushEnabled] = useState(false);
  const [pushSupported, setPushSupported] = useState(false);
  const [pushSubscribed, setPushSubscribed] = useState(false);
  const [pushBusy, setPushBusy] = useState(false);

  const refresh = useCallback(async () => {
    try { setProducts(await api.products()); setError(''); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load products.'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    if (!products.some((product) => product.availability === 'CHECKING')) return;
    const timer = window.setInterval(() => { void refresh(); }, 2000);
    return () => window.clearInterval(timer);
  }, [products, refresh]);

  useEffect(() => {
    const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
    setPushSupported(supported);
    if (!supported) return;
    void api.pushConfig().then(async (config) => {
      setPushEnabled(config.enabled);
      if (!config.enabled) return;
      const registration = await navigator.serviceWorker.register('/sw.js');
      const subscription = await registration.pushManager.getSubscription();
      setPushSubscribed(subscription != null);
    }).catch((reason) => setError(reason instanceof Error ? reason.message : 'Could not initialize browser notifications.'));
  }, []);

  async function togglePush() {
    if (!pushSupported || !pushEnabled) return;
    setPushBusy(true); setError('');
    try {
      const registration = await navigator.serviceWorker.ready;
      const existing = await registration.pushManager.getSubscription();
      if (existing) {
        await api.removePushSubscription(existing.endpoint);
        await existing.unsubscribe();
        setPushSubscribed(false);
        return;
      }
      const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
      if (permission !== 'granted') throw new Error('Allow notifications for this site in Chrome to enable price alerts.');
      const config = await api.pushConfig();
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: decodeVapidKey(config.publicKey),
      });
      await api.savePushSubscription(subscription.toJSON());
      setPushSubscribed(true);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not update browser notification settings.'); }
    finally { setPushBusy(false); }
  }

  async function sendTestPush() {
    setPushBusy(true); setError('');
    try { await api.testPush(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not send a test notification.'); }
    finally { setPushBusy(false); }
  }

  const stats = useMemo(() => {
    const checked = products.filter((product) => product.currentPrice != null);
    const dropped = checked.filter((product) => product.history.length > 1
      && product.currentPrice! < product.history[1].amount).length;
    return { tracked: products.length, checked: checked.length, dropped };
  }, [products]);

  const visibleProducts = useMemo(() => {
    const query = searchTerm.trim().toLocaleLowerCase();
    const matching = products.filter((product) => !query
      || product.name.toLocaleLowerCase().includes(query)
      || new URL(product.url).hostname.toLocaleLowerCase().includes(query));
    return matching.sort((left, right) => {
      if (sortMode === 'recent') return new Date(right.lastCheckedAt ?? 0).getTime() - new Date(left.lastCheckedAt ?? 0).getTime();
      const leftPrice = left.currentPrice;
      const rightPrice = right.currentPrice;
      if (leftPrice == null) return rightPrice == null ? 0 : 1;
      if (rightPrice == null) return -1;
      return sortMode === 'low' ? leftPrice - rightPrice : rightPrice - leftPrice;
    });
  }, [products, searchTerm, sortMode]);

  function closeForm() {
    setShowForm(false); setEditingId(null); setForm(emptyForm); setError('');
  }

  function editProduct(product: Product) {
    setEditingId(product.id);
    setForm({ name: product.name, url: product.url, targetPrice: product.targetPrice?.toString() ?? '', currency: product.currency, priceSelector: product.priceSelector ?? '' });
    setShowForm(true); setError('');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  async function saveProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaving(true); setError('');
    try {
      if (editingId != null) {
        const updated = await api.update(editingId, form);
        setProducts((current) => current.map((product) => product.id === editingId ? updated : product));
      } else {
        const created = await api.create(form);
        setProducts((current) => [created, ...current]);
      }
      closeForm();
    } catch (reason) { setError(reason instanceof Error ? reason.message : `Could not ${editingId == null ? 'add' : 'update'} this product.`); }
    finally { setSaving(false); }
  }

  async function checkProduct(id: number) {
    setBusyId(id); setError('');
    try {
      const updated = await api.check(id);
      setProducts((current) => current.map((product) => product.id === id ? updated : product));
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Price check failed.'); }
    finally { setBusyId(null); }
  }

  async function deleteProduct(id: number) {
    if (!window.confirm('Remove this product and its price history?')) return;
    try { await api.remove(id); setProducts((current) => current.filter((product) => product.id !== id)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not remove this product.'); }
  }

  return (
    <main className="shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="Price Monitor home"><span className="brand-mark">$</span><span>price<span className="brand-light">monitor</span></span></a>
        <div className="top-actions"><span className="live-dot" /> Local workspace
          {pushSupported && <div className="push-controls">
            {!pushEnabled ? <span className="push-hint">Set VAPID keys in .env to enable alerts</span> : <>
              <button className="secondary-button push-button" onClick={togglePush} disabled={pushBusy}>
                {pushBusy ? 'Please wait…' : pushSubscribed ? 'Disable alerts' : 'Enable Chrome alerts'}
              </button>
              {pushSubscribed && <button className="secondary-button push-button" onClick={sendTestPush} disabled={pushBusy}>Send test</button>}
            </>}
          </div>}
          <button className="avatar" aria-label="Account">A</button></div>
      </header>

      <section className="hero">
        <div className="hero-copy"><div className="eyebrow"><span className="hero-sparkle">✦</span> YOUR PERSONAL PRICE RADAR</div><h1>Buy it when<br /><span>the time is right.</span></h1>
          <p>Track your favorites, spot price drops, and keep every deal in view.</p><div className="hero-proof"><span><i /> Automatic price checks</span><span><i /> Your data stays local</span></div></div>
        <div className="hero-action"><div className="hero-orbit orbit-one"/><div className="hero-orbit orbit-two"/><div className="hero-price-note"><span className="hero-price-icon">↘</span><span><b>Good things come down.</b><small>Your watchlist is ready when they do.</small></span></div><button className="primary-button" onClick={() => { if (showForm) closeForm(); else { setEditingId(null); setForm(emptyForm); setShowForm(true); } }}><span className="plus">+</span> Track a product</button></div>
      </section>

      <section className="stat-grid" aria-label="Watchlist overview">
        <Stat label="ON YOUR RADAR" value={stats.tracked.toString().padStart(2, '0')} note="Products you're following" icon="◇" />
        <Stat label="LIVE PRICES" value={stats.checked.toString().padStart(2, '0')} note="With a saved price" icon="◷" />
        <Stat label="PRICE DROPS" value={stats.dropped.toString().padStart(2, '0')} note="Lower than the prior check" icon="↘" accent />
      </section>

      {showForm && <form className="add-card" onSubmit={saveProduct}>
        <div className="form-heading"><div><div className="eyebrow">{editingId == null ? 'NEW TRACKER' : 'UPDATE TRACKER'}</div><h2>{editingId == null ? 'Add a product' : 'Edit product'}</h2></div><button type="button" className="icon-button" onClick={closeForm} aria-label="Close">×</button></div>
        <div className="form-grid">
          <label>Product name<input required maxLength={160} placeholder="e.g. Wireless headphones" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>Product page URL<input required type="url" placeholder="https://store.example/product" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} /></label>
          <label>Target price <span className="optional">optional</span><input type="number" min="0.01" step="0.01" placeholder="150.00" value={form.targetPrice} onChange={(e) => setForm({ ...form, targetPrice: e.target.value })} /></label>
          <label>Currency<input required minLength={3} maxLength={3} value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} /></label>
          <label className="wide">Price CSS selector <span className="optional">optional · for pages with unusual layouts</span><input maxLength={128} placeholder=".price, [data-testid='price']" value={form.priceSelector} onChange={(e) => setForm({ ...form, priceSelector: e.target.value })} /></label>
        </div>
        <div className="form-footer"><span>{editingId == null ? 'We’ll save it immediately, then check price and stock in the background and every 30 minutes.' : 'Your existing price history and check schedule will be kept.'}</span><button className="primary-button" disabled={saving}>{saving ? 'Saving…' : editingId == null ? 'Add to watchlist' : 'Save changes'}</button></div>
      </form>}

      {error && <div className="alert" role="alert"><span>{error}</span><button onClick={() => setError('')} aria-label="Dismiss">×</button></div>}

      <section className="watchlist">
        <div className="section-heading"><div><div className="eyebrow">THE LINEUP</div><h2>Your watchlist</h2><p>Everything you're keeping an eye on.</p></div><span className="count-pill">{visibleProducts.length}{searchTerm ? ` of ${products.length}` : ''} {products.length === 1 ? 'item' : 'items'}</span></div>
        {!loading && products.length > 0 && <div className="watchlist-tools"><label className="search-box"><span aria-hidden="true">⌕</span><input type="search" placeholder="Find a product or store" aria-label="Search watchlist" value={searchTerm} onChange={(event) => setSearchTerm(event.target.value)} />{searchTerm && <button type="button" onClick={() => setSearchTerm('')} aria-label="Clear search">×</button>}</label><label className="sort-box"><span>Sort by</span><select aria-label="Sort watchlist" value={sortMode} onChange={(event) => setSortMode(event.target.value as 'recent' | 'low' | 'high')}><option value="recent">Recently checked</option><option value="low">Price: low to high</option><option value="high">Price: high to low</option></select></label></div>}
        {loading ? <div className="empty-state">Loading your watchlist…</div> : products.length === 0 ? <div className="empty-state"><div className="empty-icon">↘</div><h3>Your watchlist is a blank slate.</h3><p>Add a product URL and we’ll start keeping an eye on its price.</p><button className="secondary-button" onClick={() => setShowForm(true)}>Add your first product</button></div> :
          visibleProducts.length === 0 ? <div className="empty-state filtered-empty"><div className="empty-icon">⌕</div><h3>No matching products.</h3><p>Try another product name or store.</p><button className="secondary-button" onClick={() => setSearchTerm('')}>Clear search</button></div> : <div className="product-list">{visibleProducts.map((product) => <ProductCard key={product.id} product={product} busy={busyId === product.id} onCheck={checkProduct} onEdit={editProduct} onDelete={deleteProduct} />)}</div>}
      </section>
      <footer><span>PRICE MONITOR</span><span>Private by default · Runs on your machine</span></footer>
    </main>
  );
}

function decodeVapidKey(key: string): ArrayBuffer {
  const padding = '='.repeat((4 - key.length % 4) % 4);
  const base64 = (key + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = window.atob(base64);
  const decoded = new Uint8Array(raw.length);
  for (let index = 0; index < raw.length; index += 1) decoded[index] = raw.charCodeAt(index);
  return decoded.buffer as ArrayBuffer;
}

function Stat({ label, value, note, icon, accent = false }: { label: string; value: string; note: string; icon: string; accent?: boolean }) {
  return <article className={`stat-card${accent ? ' stat-accent' : ''}`}><div className="stat-top"><div className="eyebrow">{label}</div><span className="stat-icon" aria-hidden="true">{icon}</span></div><div className="stat-value">{value}</div><div className="stat-note">{note}</div></article>;
}

function ProductCard({ product, busy, onCheck, onEdit, onDelete }: { product: Product; busy: boolean; onCheck: (id: number) => void; onEdit: (product: Product) => void; onDelete: (id: number) => void }) {
  const [historyOpen, setHistoryOpen] = useState(false);
  const hasProductPage = isDirectProductPage(product.url);
  const availability = product.availability ?? 'UNKNOWN';
  const availabilityLabel = availability === 'IN_STOCK' ? 'In stock'
    : availability === 'OUT_OF_STOCK' ? 'Out of stock'
      : availability === 'CHECKING' ? 'Checking stock…' : 'Availability unknown';
  const previous = product.history[1]?.amount;
  const trend = previous != null && product.currentPrice != null
    ? product.currentPrice < previous ? 'down' : product.currentPrice > previous ? 'up' : 'steady'
    : 'new';
  const change = previous != null && product.currentPrice != null ? Math.abs(product.currentPrice - previous) : null;
  const trendLabel = change == null ? 'Awaiting first reading'
    : change === 0 ? 'No change since previous check'
      : `${currency(change, product.currency)} since previous check`;
  return <article className="product-card">
    <div className="product-visual">{product.imageUrl ? <img src={product.imageUrl} alt="" /> : <div className="visual-placeholder">◇</div>}</div>
    <div className="product-main"><div className="product-title-row">{hasProductPage ? <a href={product.url} target="_blank" rel="noreferrer" className="product-title">{product.name}</a> : <button type="button" className="product-title title-edit-action" onClick={() => onEdit(product)} title="Set a direct product page URL before opening">{product.name}</button>}{product.targetPrice != null && <span className="target-chip">Target {currency(product.targetPrice, product.currency)}</span>}</div>
      <div className="product-link-row"><div className="product-url">{hasProductPage ? new URL(product.url).hostname : 'Add a direct product page URL'}</div><span className={`availability-pill availability-${availability.toLowerCase()}`} title={product.availabilityCheckedAt ? `Last confirmed ${date(product.availabilityCheckedAt)}` : undefined}>{availabilityLabel}</span></div>
      {product.lastCheckError && <div className="product-error">{product.lastCheckError}</div>}
      <div className="product-meta"><span>{product.history.length} checks in 3 months</span><span className="meta-separator">·</span><span>Last checked {date(product.lastCheckedAt)}</span></div>
    </div>
    <div className="product-price"><div className="eyebrow">CURRENT PRICE</div><div className="price-value">{currency(product.currentPrice, product.currency)}</div>
      <div className={`trend trend-${trend}`}>{trend === 'down' ? '↘' : trend === 'up' ? '↗' : trend === 'steady' ? '→' : '•'} {trendLabel}</div></div>
    <div className="product-actions"><button className="icon-button" onClick={() => onCheck(product.id)} disabled={busy} title="Check price now" aria-label="Check price now">{busy ? '…' : '↻'}</button><button className="icon-button edit-button" onClick={() => onEdit(product)} title="Edit product" aria-label={`Edit ${product.name}`}>✎</button><button className="icon-button delete-button" onClick={() => onDelete(product.id)} title="Remove product" aria-label="Remove product">×</button></div>
    <div className="history-toggle-row"><button className="history-toggle" type="button" aria-expanded={historyOpen} onClick={() => setHistoryOpen((open) => !open)}>{historyOpen ? 'Hide price history' : 'View 3-month price history'}<span aria-hidden="true">{historyOpen ? '⌃' : '⌄'}</span></button></div>
    {historyOpen && <PriceHistoryPanel product={product} />}
  </article>;
}

function PriceHistoryPanel({ product }: { product: Product }) {
  const points = [...product.history]
    .filter((point) => {
      const age = Date.now() - new Date(point.checkedAt).getTime();
      return age >= 0 && age <= 90 * 24 * 60 * 60 * 1000;
    })
    .sort((left, right) => new Date(left.checkedAt).getTime() - new Date(right.checkedAt).getTime());

  if (points.length === 0) {
    return <section className="history-panel" aria-label="Three-month price history">
      <div className="history-heading"><div><div className="eyebrow">PRICE HISTORY</div><h3>Last 3 months</h3></div></div>
      <div className="history-empty">No price readings in the last 3 months. New successful checks will appear here.</div>
    </section>;
  }

  const amounts = points.map((point) => point.amount);
  const low = Math.min(...amounts);
  const high = Math.max(...amounts);
  const latest = points[points.length - 1];
  const firstTime = new Date(points[0].checkedAt).getTime();
  const lastTime = new Date(latest.checkedAt).getTime();
  const timeSpan = lastTime - firstTime;
  const rawRange = high - low;
  const padding = rawRange === 0 ? Math.max(high * 0.08, 1) : rawRange * 0.16;
  const minY = Math.max(0, low - padding);
  const maxY = high + padding;
  const chartLeft = 64;
  const chartRight = 620;
  const chartTop = 16;
  const chartBottom = 166;
  const xFor = (timestamp: number, index: number) => points.length === 1
    ? (chartLeft + chartRight) / 2
    : timeSpan === 0
      ? chartLeft + (index / (points.length - 1)) * (chartRight - chartLeft)
      : chartLeft + ((timestamp - firstTime) / timeSpan) * (chartRight - chartLeft);
  const yFor = (amount: number) => chartBottom - ((amount - minY) / (maxY - minY)) * (chartBottom - chartTop);
  const coordinates = points.map((point, index) => ({
    x: xFor(new Date(point.checkedAt).getTime(), index),
    y: yFor(point.amount),
    point,
  }));
  const line = coordinates.map(({ x, y }, index) => `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`).join(' ');
  const area = `${line} L ${coordinates[coordinates.length - 1].x.toFixed(2)} ${chartBottom} L ${coordinates[0].x.toFixed(2)} ${chartBottom} Z`;
  const yTicks = [maxY, (maxY + minY) / 2, minY];
  const shortCurrency = (amount: number) => new Intl.NumberFormat(undefined, {
    style: 'currency', currency: product.currency, notation: 'compact', maximumFractionDigits: 1,
  }).format(amount);
  const shortDate = (value: string) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(new Date(value));

  return <section className="history-panel" aria-label="Three-month price history">
    <div className="history-heading"><div><div className="eyebrow">PRICE HISTORY</div><h3>Last 3 months</h3></div><span>{points.length} {points.length === 1 ? 'reading' : 'readings'}</span></div>
    <div className="history-summary">
      <div><span>Lowest</span><strong>{currency(low, product.currency)}</strong></div>
      <div><span>Highest</span><strong>{currency(high, product.currency)}</strong></div>
      <div><span>Latest</span><strong>{currency(latest.amount, latest.currency)}</strong></div>
    </div>
    <div className="history-chart-wrap">
      <svg className="history-chart" viewBox="0 0 640 210" role="img" aria-label={`Price history for ${product.name}: ${points.length} readings over the last 3 months`}>
        <defs><linearGradient id={`history-fill-${product.id}`} x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="#5d8a69" stopOpacity=".2"/><stop offset="100%" stopColor="#5d8a69" stopOpacity=".015"/></linearGradient></defs>
        {yTicks.map((tick, index) => {
          const y = yFor(tick);
          return <g key={index}><line x1={chartLeft} x2={chartRight} y1={y} y2={y} className="chart-gridline"/><text x={chartLeft - 9} y={y + 4} textAnchor="end" className="chart-label">{shortCurrency(tick)}</text></g>;
        })}
        {points.length > 1 && <path d={area} fill={`url(#history-fill-${product.id})`} />}
        {points.length > 1 && <path d={line} className="chart-line" />}
        {coordinates.map(({ x, y, point }, index) => <circle key={`${point.checkedAt}-${index}`} cx={x} cy={y} r={points.length === 1 ? 5 : 3.5} className="chart-point"><title>{date(point.checkedAt)} · {currency(point.amount, point.currency)}</title></circle>)}
        <text x={chartLeft} y="195" className="chart-label">{shortDate(points[0].checkedAt)}</text>
        {points.length > 1 && <text x={chartRight} y="195" textAnchor="end" className="chart-label">{shortDate(latest.checkedAt)}</text>}
      </svg>
    </div>
    {points.length === 1 && <p className="history-note">One reading so far. The trend line will appear after another successful check.</p>}
  </section>;
}
