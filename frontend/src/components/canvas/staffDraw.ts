/** Pixi drawing of the compact staff on snippet cards (shapes from components/score/compactStaff.ts). */
import { TextStyle } from 'pixi.js';
import type { Graphics as PixiGraphics } from 'pixi.js';
import type { CompactStaffShapes } from '@/components/score/compactStaff';
import { COMPACT_STAFF, STAFF_COLORS } from '@/components/score/staffGeometry';

const hex = (color: string) => parseInt(color.replace('#', ''), 16);

export function drawCompactStaff(g: PixiGraphics, shapes: CompactStaffShapes): void {
  g.lineStyle(1, hex(STAFF_COLORS.staff), 0.9);
  for (const l of shapes.staffLines) {
    g.moveTo(l.x1, l.y1);
    g.lineTo(l.x2, l.y2);
  }
  g.lineStyle(1, hex(STAFF_COLORS.barLine), 0.9);
  for (const l of shapes.barLines) {
    g.moveTo(l.x1, l.y1);
    g.lineTo(l.x2, l.y2);
  }
  g.lineStyle(1, hex(STAFF_COLORS.beat), 0.9);
  for (const l of shapes.beatMarks) {
    g.moveTo(l.x1, l.y1);
    g.lineTo(l.x2, l.y2);
  }
  g.lineStyle(0);
  g.beginFill(hex(STAFF_COLORS.note), 0.75);
  for (const b of shapes.noteBars) g.drawRect(b.x, b.y, b.w, b.h);
  g.endFill();
  g.beginFill(hex(STAFF_COLORS.note), 1);
  for (const head of shapes.heads) g.drawEllipse(head.x, head.y, head.rx, head.ry);
  g.endFill();
}

export interface StaffTextStyles {
  text: TextStyle;
  bold: TextStyle;
  accent: TextStyle;
}

export function createStaffTextStyles(s: number): StaffTextStyles {
  const size = COMPACT_STAFF.fontSize * s;
  return {
    text: new TextStyle({ fontSize: size, fill: STAFF_COLORS.text, fontFamily: 'monospace' }),
    bold: new TextStyle({ fontSize: size, fill: '#ffffff', fontWeight: 'bold', fontFamily: 'monospace' }),
    accent: new TextStyle({ fontSize: size, fill: STAFF_COLORS.accent, fontWeight: 'bold', fontFamily: 'monospace' }),
  };
}
