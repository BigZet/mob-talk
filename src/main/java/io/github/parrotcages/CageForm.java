package io.github.parrotcages;

/**
 * Форма птичьей клетки в блоках от угла: круглый поддон, прямые стенки и купол-полуэллипсоид.
 * Одна форма задаёт и рисунок прутьев, и столкновения, поэтому птица не пролетает сквозь прутья.
 */
public record CageForm(int width, int height) {
	public double centerX() { return width / 2.0; }
	public double centerZ() { return width / 2.0; }
	public double radius() { return width / 2.0 - (width == 1 ? 0.06 : 0.1); }
	/** Верх поддона. */
	public double floor() { return 0.125; }
	/** Где стенки переходят в купол. */
	public double wallTop() { return height * 0.58; }
	/** Макушка купола; выше неё только кольцо-подвес. */
	public double apex() { return height - (width == 1 ? 0.16 : 0.24); }

	/** Радиус клетки на высоте {@code y}; отрицательный — вне клетки по высоте. */
	public double radiusAt(double y) {
		if (y < floor() || y > apex()) return -1;
		if (y <= wallTop()) return radius();
		double t = (y - wallTop()) / (apex() - wallTop());
		return radius() * Math.sqrt(Math.max(0, 1 - t * t));
	}

	/** Точка внутри клетки с запасом {@code margin} до прутьев. */
	public boolean inside(double x, double y, double z, double margin) {
		double r = radiusAt(y);
		if (r < 0 || y > apex() - margin) return false;
		double dx = x - centerX(), dz = z - centerZ();
		return Math.sqrt(dx * dx + dz * dz) <= r - margin;
	}
}
