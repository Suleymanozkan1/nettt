import { PrismaClient } from '@prisma/client';

// Usage: pnpm --filter @stage/api exec tsx scripts/make-admin.ts <email>
const email = process.argv[2];
if (!email) {
  console.error('usage: make-admin.ts <email>');
  process.exit(1);
}
const prisma = new PrismaClient();
await prisma.user.update({ where: { email: email.toLowerCase() }, data: { role: 'ADMIN' } });
console.warn(`${email} is now ADMIN`);
await prisma.$disconnect();
