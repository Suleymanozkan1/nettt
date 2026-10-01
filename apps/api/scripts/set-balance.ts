import { PrismaClient } from '@prisma/client';

// Dev/test tool: pnpm --filter @stage/api exec tsx --env-file-if-exists=.env scripts/set-balance.ts <userId> <credits|gems> <amount>
const [userId, currency, amount] = process.argv.slice(2);
if (!userId || (currency !== 'credits' && currency !== 'gems') || !amount || !/^\d+$/.test(amount)) {
  console.error('usage: set-balance.ts <userId> <credits|gems> <amount>');
  process.exit(1);
}
const prisma = new PrismaClient();
await prisma.user.update({ where: { id: userId }, data: { [currency]: Number(amount) } });
await prisma.$disconnect();
