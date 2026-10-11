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

export default function App() {
  const [products, setProducts] = useState<Product[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);
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
        <div><div className="eyebrow">YOUR WATCHLIST</div><h1>Make every price<br /><span>drop count.</span></h1>
          <p>One calm place to follow the products you want.</p></div>
        <button className="primary-button" onClick={() => { if (showForm) closeForm(); else { setEditingId(null); setForm(emptyForm); setShowForm(true); } }}><span className="plus">+</span> Track a product</button>
      </section>

      <section className="stat-grid" aria-label="Watchlist overview">
        <Stat label="TRACKED PRODUCTS" value={stats.tracked.toString().padStart(2, '0')} note="Across your watchlist" />
        <Stat label="PRICES FOUND" value={stats.checked.toString().padStart(2, '0')} note="With a current price" />
        <Stat label="PRICE DROPS" value={stats.dropped.toString().padStart(2, '0')} note="Since the last check" accent />
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
        <div className="form-footer"><span>{editingId == null ? 'We’ll check the page now and every 30 minutes.' : 'Your existing price history and check schedule will be kept.'}</span><button className="primary-button" disabled={saving}>{saving ? 'Saving…' : editingId == null ? 'Add to watchlist' : 'Save changes'}</button></div>
      </form>}

      {error && <div className="alert" role="alert"><span>{error}</span><button onClick={() => setError('')} aria-label="Dismiss">×</button></div>}

      <section className="watchlist">
        <div className="section-heading"><div><div className="eyebrow">THE LINEUP</div><h2>Your watchlist</h2></div><span className="count-pill">{products.length} {products.length === 1 ? 'item' : 'items'}</span></div>
        {loading ? <div className="empty-state">Loading your watchlist…</div> : products.length === 0 ? <div className="empty-state"><div className="empty-icon">↘</div><h3>Your watchlist is a blank slate.</h3><p>Add a product URL and we’ll start keeping an eye on its price.</p><button className="secondary-button" onClick={() => setShowForm(true)}>Add your first product</button></div> :
          <div className="product-list">{products.map((product) => <ProductCard key={product.id} product={product} busy={busyId === product.id} onCheck={checkProduct} onEdit={editProduct} onDelete={deleteProduct} />)}</div>}
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

function Stat({ label, value, note, accent = false }: { label: string; value: string; note: string; accent?: boolean }) {
  return <article className={`stat-card${accent ? ' stat-accent' : ''}`}><div className="eyebrow">{label}</div><div className="stat-value">{value}</div><div className="stat-note">{note}</div></article>;
}

function ProductCard({ product, busy, onCheck, onEdit, onDelete }: { product: Product; busy: boolean; onCheck: (id: number) => void; onEdit: (product: Product) => void; onDelete: (id: number) => void }) {
  const previous = product.history[1]?.amount;
  const trend = previous != null && product.currentPrice != null
    ? product.currentPrice < previous ? 'down' : product.currentPrice > previous ? 'up' : 'steady'
    : 'new';
  const change = previous != null && product.currentPrice != null ? Math.abs(product.currentPrice - previous) : null;
  return <article className="product-card">
    <div className="product-visual">{product.imageUrl ? <img src={product.imageUrl} alt="" /> : <div className="visual-placeholder">◇</div>}</div>
    <div className="product-main"><div className="product-title-row"><a href={product.url} target="_blank" rel="noreferrer" className="product-title">{product.name}</a>{product.targetPrice != null && <span className="target-chip">Target {currency(product.targetPrice, product.currency)}</span>}</div>
      <div className="product-url">{new URL(product.url).hostname}</div>
      {product.lastCheckError && <div className="product-error">{product.lastCheckError}</div>}
      <div className="product-meta"><span>{product.history.length} checks</span><span className="meta-separator">·</span><span>Last checked {date(product.lastCheckedAt)}</span></div>
    </div>
    <div className="product-price"><div className="eyebrow">CURRENT PRICE</div><div className="price-value">{currency(product.currentPrice, product.currency)}</div>
      <div className={`trend trend-${trend}`}>{trend === 'down' ? '↘' : trend === 'up' ? '↗' : trend === 'steady' ? '→' : '•'} {change == null ? 'Awaiting first reading' : `${currency(change, product.currency)} since previous check`}</div></div>
    <div className="product-actions"><button className="icon-button" onClick={() => onCheck(product.id)} disabled={busy} title="Check price now" aria-label="Check price now">{busy ? '…' : '↻'}</button><button className="icon-button edit-button" onClick={() => onEdit(product)} title="Edit product" aria-label={`Edit ${product.name}`}>✎</button><button className="icon-button delete-button" onClick={() => onDelete(product.id)} title="Remove product" aria-label="Remove product">×</button></div>
  </article>;
}
