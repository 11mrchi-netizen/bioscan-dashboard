-- P5: stool tracker — Bristol Stool Scale (standard clinical 1-7 classification)
create table stool_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  occurred_at timestamptz not null,
  bristol_type smallint not null check (bristol_type between 1 and 7),
  discomfort smallint check (discomfort between 0 and 10),
  notes text,
  created_at timestamptz not null default now()
);

-- P6: wardrobe manager
create table clothing_items (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  category text not null check (category in ('top','bottom','outerwear','footwear','base-layer','accessory')),
  warmth_rating smallint check (warmth_rating between 0 and 10),
  waterproof boolean default false,
  formality text,
  color text,
  status text not null check (status in ('clean','worn-unwashed','in-laundry')) default 'clean',
  wear_count_since_wash integer not null default 0,
  last_worn_date date,
  tags text[],
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table laundry_loads (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  done_at timestamptz not null default now(),
  item_ids bigint[] not null,
  notes text,
  created_at timestamptz not null default now()
);
