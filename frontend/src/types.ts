export type PricePoint = {
  amount: number;
  currency: string;
  checkedAt: string;
};

export type Product = {
  id: number;
  name: string;
  url: string;
  imageUrl: string | null;
  priceSelector: string | null;
  targetPrice: number | null;
  currency: string;
  currentPrice: number | null;
  lastCheckedAt: string | null;
  lastCheckError: string | null;
  active: boolean;
  history: PricePoint[];
};

export type ProductInput = {
  name: string;
  url: string;
  targetPrice: string;
  currency: string;
  priceSelector: string;
};
