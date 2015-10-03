package hex.util;

import Jama.CholeskyDecomposition;
import Jama.Matrix;
import hex.DataInfo;
import hex.FrameTask;
import hex.gram.Gram;
import hex.gram.Gram.Cholesky;
import water.Key;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.util.ArrayUtils;
import water.util.Log;

import java.util.Arrays;

public class LinearAlgebraUtils {

  // Regularized Cholesky decomposition using H2O implementation
  public static Cholesky regularizedCholesky(Gram gram, int max_attempts) {
    int attempts = 0;
    double addedL2 = 0;   // TODO: Should I report this to the user?
    Cholesky chol = gram.cholesky(null);

    while(!chol.isSPD() && attempts < max_attempts) {
      if(addedL2 == 0) addedL2 = 1e-5;
      else addedL2 *= 10;
      ++attempts;
      gram.addDiag(addedL2); // try to add L2 penalty to make the Gram SPD
      Log.info("Added L2 regularization = " + addedL2 + " to diagonal of Gram matrix");
      gram.cholesky(chol);
    }
    if(!chol.isSPD())
      throw new Gram.NonSPDMatrixException();
    return chol;
  }
  public static Cholesky regularizedCholesky(Gram gram) { return regularizedCholesky(gram, 10); }

  // Regularized Cholesky decomposition using JAMA implementation
  public static CholeskyDecomposition regularizedCholesky(double[][] gram, int max_attempts, boolean throw_exception) {
    int attempts = 0;
    double addedL2 = 0;
    Matrix gmat = new Matrix(gram);
    CholeskyDecomposition chol = new CholeskyDecomposition(gmat);

    while(!chol.isSPD() && attempts < max_attempts) {
      if(addedL2 == 0) addedL2 = 1e-5;
      else addedL2 *= 10;
      ++attempts;

      for(int i = 0; i < gram.length; i++) gmat.set(i,i,addedL2); // try to add L2 penalty to make the Gram SPD
      Log.info("Added L2 regularization = " + addedL2 + " to diagonal of Gram matrix");
      chol = new CholeskyDecomposition(gmat);
    }
    if(!chol.isSPD() && throw_exception)
      throw new Gram.NonSPDMatrixException();
    return chol;
  }
  public static CholeskyDecomposition regularizedCholesky(double[][] gram) { return regularizedCholesky(gram, 10, true); }

  /*
   * Forward substitution: Solve Lx = b for x with L = lower triangular matrix, b = real vector
   */
  public static final double[] forwardSolve(double[][] L, double[] b) {
    assert L != null && L.length == L[0].length && L.length == b.length;
    double[] res = new double[b.length];

    for(int i = 0; i < b.length; i++) {
      res[i] = b[i];
      for(int j = 0; j < i; j++)
        res[i] -= L[i][j] * res[j];
      res[i] /= L[i][i];
    }
    return res;
  }

  /*
   * Backward substitution: Solve Ux = b for x = U = upper triangular matrix, b = real vector
   */
  public static final double[] backwardSolve(double[][] U, double[] b) {
    assert U != null && U.length == U[0].length && U.length == b.length;
    double[] res = new double[b.length];

    for(int i = b.length-1; i >= 0; i--) {
      res[i] = b[i];
      for(int j = i+1; j < b.length; j++)
        res[i] -= U[i][j] * res[j];
      res[i] /= U[i][i];
    }
    return res;
  }

  /*
   * Impute missing values and transform numeric value x in col of dinfo._adaptedFrame
   */
  private static double modifyNumeric(double x, int col, DataInfo dinfo) {
    double y = x;
    if (Double.isNaN(x) && dinfo._imputeMissing)  // Impute missing value with mean
      y = dinfo._numMeans[col];
    if (dinfo._normSub != null && dinfo._normMul != null)  // Transform x if requested
      y = (y - dinfo._normSub[col]) * dinfo._normMul[col];
    return y;
  }

  /*
   * Return row with categoricals expanded in array tmp
   */
  public static double[] expandRow(double[] row, DataInfo dinfo, double[] tmp) { return expandRow(row, dinfo, tmp, true); }
  public static double[] expandRow(double[] row, DataInfo dinfo, double[] tmp, boolean modify_numeric) {
    assert row != null && row.length == dinfo._adaptedFrame.numCols();

    // Categorical columns
    int cidx;
    for(int col = 0; col < dinfo._cats; col++) {
      if (Double.isNaN(row[col])) {
        if (dinfo._imputeMissing)
          cidx = dinfo._catModes[col];
        else if (dinfo._catMissing[col] == 0)
          continue;   // Skip if entry missing and no NA bucket. All indicators will be zero.
        else
          cidx = dinfo._catOffsets[col+1]-1;  // Otherwise, missing value turns into extra (last) factor
      } else
        cidx = dinfo.getCategoricalId(col, (int)row[col]);
      if(cidx >= 0) tmp[cidx] = 1;
    }

    // Numeric columns
    int chk_cnt = dinfo._cats;
    int exp_cnt = dinfo.numStart();
    for(int col = 0; col < dinfo._nums; col++) {
      // Only do imputation and transformation if requested
      tmp[exp_cnt] = modify_numeric ? modifyNumeric(row[chk_cnt], col, dinfo) : row[chk_cnt];
      exp_cnt++; chk_cnt++;
    }
    return tmp;
  }

  public static double[] expandRow(double[] row, DataInfo dinfo, double[] tmp, double[][] offset, double[] scale) {
    if(offset == null && scale == null)
      return expandRow(row, dinfo, tmp, false);

    assert row != null && row.length == dinfo._adaptedFrame.numCols();
    double[][] means = offset == null ? new double[row.length][] : offset;
    double[] mults = scale == null ? new double[row.length] : scale;
    if(scale == null) Arrays.fill(mults, 1.0);
    int[] numLevels = dinfo._adaptedFrame.cardinality();

    // Categorical columns
    int cidx;
    for(int col = 0; col < dinfo._cats; col++) {
      if (Double.isNaN(row[col])) {
        if (dinfo._imputeMissing)
          cidx = dinfo._catModes[col];
        else if (dinfo._catMissing[col] == 0)
          continue;   // Skip if entry missing and no NA bucket. All indicators will be zero.
        else
          cidx = dinfo._catOffsets[col+1]-1;  // Otherwise, missing value turns into extra (last) factor
      } else
        cidx = dinfo.getCategoricalId(col, (int)row[col]);
      if(cidx >= 0) tmp[cidx] = 1;

      // Offset each of the cols corresponding to expanded categorical level
      int cat_cnt = dinfo._catOffsets[col];
      assert means[col] == null || means[col].length == numLevels[col];
      for(int level = 0; level < numLevels[col]; level++) {
        tmp[cat_cnt] = (tmp[cat_cnt] - (means[col] != null ? means[col][level] : 0)) * mults[col];
        cat_cnt++;
      }
      assert cat_cnt == dinfo._catOffsets[col+1];
    }

    // Numeric columns
    int chk_cnt = dinfo._cats;
    int exp_cnt = dinfo.numStart();
    for(int col = 0; col < dinfo._nums; col++) {
      tmp[exp_cnt] = (row[chk_cnt] - (means[chk_cnt] != null ? means[chk_cnt][0] : 0)) * mults[chk_cnt];
      exp_cnt++; chk_cnt++;
    }
    return tmp;
  }

  /**
   * Computes B = XY where X is n by k and Y is k by p, saving result in new vecs
   * Input: dinfo = X (large frame) with dinfo._adaptedFrame passed to doAll
   *        yt = Y' = transpose of Y (small matrix)
   * Output: XY (large frame) is n by p
   */
  public static class BMulTask extends FrameTask<BMulTask> {
    final double[][] _yt;   // _yt = Y' (transpose of Y)

    public BMulTask(Key jobKey, DataInfo dinfo, double[][] yt) {
      super(jobKey, dinfo);
      _yt = yt;
    }

    @Override protected void processRow(long gid, DataInfo.Row row, NewChunk[] outputs) {
      for(int p = 0; p < _yt.length; p++) {
        double x = row.innerProduct(_yt[p]);
        outputs[p].addNum(x);
      }
    }
  }

  /**
   * Computes B = XY where X is n by k and Y is k by p, saving result in same frame
   * Input: [X,B] (large frame) passed to doAll, where we write to B
   *        yt = Y' = transpose of Y (small matrix)
   *        ncolX = number of columns in X
   */
  public static class BMulInPlaceTask extends MRTask<BMulInPlaceTask> {
    final DataInfo _xinfo;  // Info for frame X
    final double[][] _yt;   // _yt = Y' (transpose of Y)
    final int _ncolX;     // Number of cols in X

    public BMulInPlaceTask(DataInfo xinfo, double[][] yt) {
      assert yt != null && yt[0].length == xinfo._adaptedFrame.numColsExp();
      _xinfo = xinfo;
      _ncolX = xinfo._adaptedFrame.numCols();
      _yt = yt;
    }

    @Override public void map(Chunk[] cs) {
      assert cs.length == _ncolX + _yt.length;

      // Copy over only X frame chunks
      Chunk[] xchk = new Chunk[_ncolX];
      for(int i = 0; i < _ncolX; i++) xchk[i] = cs[i];

      double sum;
      for(int row = 0; row < cs[0]._len; row++) {
        // Extract row of X
        DataInfo.Row xrow = _xinfo.newDenseRow();
        _xinfo.extractDenseRow(xchk, row, xrow);
        if (xrow.bad) continue;

        int bidx = _ncolX;
        for (int p = 0; p < _yt.length; p++) {
          // Inner product of X row with Y column (Y' row)
          sum = xrow.innerProduct(_yt[p]);
          cs[bidx].set(row, sum);   // Save inner product to B
          bidx++;
        }
        assert bidx == cs.length;
      }
    }
  }

  /**
   * Computes A'Q where A is n by p and Q is n by k
   * Input: [A,Q] (large frame) passed to doAll
   * Output: atq = A'Q (small matrix) is \tilde{p} by k where \tilde{p} = number of cols in A with categoricals expanded
   */
  public static class SMulTask extends MRTask<SMulTask> {
    final DataInfo _ainfo;  // Info for frame A
    final int _ncolA;     // Number of cols in A
    final int _ncolExp;   // Number of cols in A with categoricals expanded
    final int _ncolQ;     // Number of cols in Q

    public double[][] _atq;    // Output: A'Q is p_exp by k, where p_exp = number of cols in A with categoricals expanded

    public SMulTask(DataInfo ainfo, int ncolQ) {
      _ainfo = ainfo;
      _ncolA = ainfo._adaptedFrame.numCols();
      _ncolExp = ainfo._adaptedFrame.numColsExp();
      _ncolQ = ncolQ;
    }

    @Override public void map(Chunk cs[]) {
      assert (_ncolA + _ncolQ) == cs.length;
      _atq = new double[_ncolExp][_ncolQ];

      for(int k = _ncolA; k < (_ncolA + _ncolQ); k++) {
        // Categorical columns
        int cidx;
        for(int p = 0; p < _ainfo._cats; p++) {
          for(int row = 0; row < cs[0]._len; row++) {
            if(cs[p].isNA(row) && _ainfo._skipMissing) continue;
            double q = cs[k].atd(row);
            double a = cs[p].atd(row);

            if (Double.isNaN(a)) {
              if (_ainfo._imputeMissing)
                cidx = _ainfo._catModes[p];
              else if (_ainfo._catMissing[p] == 0)
                continue;   // Skip if entry missing and no NA bucket. All indicators will be zero.
              else
                cidx = _ainfo._catOffsets[p+1]-1;     // Otherwise, missing value turns into extra (last) factor
            } else
              cidx = _ainfo.getCategoricalId(p, (int)a);
            if(cidx >= 0) _atq[cidx][k-_ncolA] += q;   // Ignore categorical levels outside domain
          }
        }

        // Numeric columns
        int pnum = 0;
        int pexp = _ainfo.numStart();
        for(int p = _ainfo._cats; p < _ncolA; p++) {
          for(int row = 0; row  < cs[0]._len; row++) {
            if(cs[p].isNA(row) && _ainfo._skipMissing) continue;
            double q = cs[k].atd(row);
            double a = cs[p].atd(row);
            a = modifyNumeric(a, pnum, _ainfo);
            _atq[pexp][k-_ncolA] += q * a;
          }
          pexp++; pnum++;
        }
        assert pexp == _atq.length;
      }
    }

    @Override public void reduce(SMulTask other) {
      ArrayUtils.add(_atq, other._atq);
    }
  }

  /**
   * Get R = L' from Cholesky decomposition Y'Y = LL' (same as R from Y = QR)
   * @param jobKey Job key for Gram calculation
   * @param yinfo DataInfo for Y matrix
   * @param transpose Should result be transposed to get L?
   * @return L or R matrix from Cholesky of Y Gram
   */
  public static double[][] computeR(Key jobKey, DataInfo yinfo, boolean transpose) {
    // Calculate Cholesky of Y Gram to get R' = L matrix
    Gram.GramTask gtsk = new Gram.GramTask(jobKey, yinfo);  // Gram is Y'Y/n where n = nrow(Y)
    gtsk.doAll(yinfo._adaptedFrame);
    // Gram.Cholesky chol = gtsk._gram.cholesky(null);   // If Y'Y = LL' Cholesky, then R = L'
    Matrix ygram = new Matrix(gtsk._gram.getXX());
    CholeskyDecomposition chol = new CholeskyDecomposition(ygram);

    double[][] L = chol.getL().getArray();
    ArrayUtils.mult(L, Math.sqrt(gtsk._nobs));  // Must scale since Cholesky of Y'Y/n where nobs = nrow(Y)
    return transpose ? L : ArrayUtils.transpose(L);
  }

  /**
   * Solve for Q from Y = QR factorization and write into new frame
   * @param jobKey Job key for Gram calculation
   * @param yinfo DataInfo for Y matrix
   * @param ywfrm Input frame [Y,W] where we write into W
   * @return l2 norm of Q - W, where W is old matrix in frame, Q is computed factorization
   */
  public static double computeQ(Key jobKey, DataInfo yinfo, Frame ywfrm) {
    double[][] cholL = computeR(jobKey, yinfo, true);
    ForwardSolve qrtsk = new ForwardSolve(yinfo, cholL);
    qrtsk.doAll(ywfrm);
    return qrtsk._sse;      // \sum (Q_{i,j} - W_{i,j})^2
  }

  /**
   * Solve for Q from Y = QR factorization and write into Y frame
   * @param jobKey Job key for Gram calculation
   * @param yinfo DataInfo for Y matrix
   */
  public static void computeQInPlace(Key jobKey, DataInfo yinfo) {
    double[][] cholL = computeR(jobKey, yinfo, true);
    ForwardSolveInPlace qrtsk = new ForwardSolveInPlace(yinfo, cholL);
    qrtsk.doAll(yinfo._adaptedFrame);
  }

  /**
   * Given lower triangular L, solve for Q in QL' = A (LQ' = A') using forward substitution
   * Dimensions: A is n by p, Q is n by p, R = L' is p by p
   * Input: [A,Q] (large frame) passed to doAll, where we write to Q
   */
  public static class ForwardSolve extends MRTask<ForwardSolve> {
    final DataInfo _ainfo;   // Info for frame A
    final int _ncols;     // Number of cols in A and in Q
    final double[][] _L;
    public double _sse;    // Output: Sum-of-squared difference between old and new Q

    public ForwardSolve(DataInfo ainfo, double[][] L) {
      assert L != null && L.length == L[0].length && L.length == ainfo._adaptedFrame.numCols();
      _ainfo = ainfo;
      _ncols = ainfo._adaptedFrame.numCols();
      _L = L;
      _sse = 0;
    }

    @Override public void map(Chunk cs[]) {
      assert 2 * _ncols == cs.length;

      // Copy over only A frame chunks
      Chunk[] achks = new Chunk[_ncols];
      for(int i = 0; i <_ncols; i++) achks[i] = cs[i];

      for(int row = 0; row < cs[0]._len; row++) {
        // 1) Extract single expanded row of A
        DataInfo.Row arow = _ainfo.newDenseRow();
        _ainfo.extractDenseRow(achks, row, arow);
        if (arow.bad) continue;
        double[] aexp = arow.expandCats();

        // 2) Solve for single row of Q using forward substitution
        double[] qrow = forwardSolve(_L, aexp);

        // 3) Save row of solved values into Q
        int i = 0;
        for(int d = _ncols; d < 2 * _ncols; d++) {
          double qold = cs[d].atd(row);
          double diff = qrow[i] - qold;
          _sse += diff * diff;    // Calculate SSE between Q_new and Q_old
          cs[d].set(row, qrow[i++]);
        }
        assert i == qrow.length;
      }
    }
  }

  /**
   * Given lower triangular L, solve for Q in QL' = A (LQ' = A') using forward substitution
   * Dimensions: A is n by p, Q is n by p, R = L' is p by p
   * Input: A (large frame) passed to doAll, where we overwrite each row of A with its row of Q
   */
  public static class ForwardSolveInPlace extends MRTask<ForwardSolveInPlace> {
    final DataInfo _ainfo;   // Info for frame A
    final int _ncols;     // Number of cols in A
    final double[][] _L;

    public ForwardSolveInPlace(DataInfo ainfo, double[][] L) {
      assert L != null && L.length == L[0].length && L.length == ainfo._adaptedFrame.numCols();
      _ainfo = ainfo;
      _ncols = ainfo._adaptedFrame.numCols();
      _L = L;
    }

    @Override public void map(Chunk cs[]) {
      assert _ncols == cs.length;

      // Copy over only A frame chunks
      Chunk[] achks = new Chunk[_ncols];
      for(int i = 0; i < _ncols; i++) achks[i] = cs[i];

      for(int row = 0; row < cs[0]._len; row++) {
        // 1) Extract single expanded row of A
        DataInfo.Row arow = _ainfo.newDenseRow();
        _ainfo.extractDenseRow(achks, row, arow);
        if (arow.bad) continue;
        double[] aexp = arow.expandCats();

        // 2) Solve for single row of Q using forward substitution
        double[] qrow = forwardSolve(_L, aexp);
        assert qrow.length == _ncols;

        // 3) Overwrite row of A with row of solved values Q
        for(int d = 0; d < _ncols; d++)
          cs[d].set(row, qrow[d]);
      }
    }
  }
}
